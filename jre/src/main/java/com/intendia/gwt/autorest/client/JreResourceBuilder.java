package com.intendia.gwt.autorest.client;

import static com.intendia.gwt.autorest.client.CollectorResourceVisitor.Param.expand;
import static java.util.stream.Collectors.joining;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import rx.Completable;
import rx.Observable;
import rx.Single;

public class JreResourceBuilder extends CollectorResourceVisitor {
    private final ConnectionFactory factory;
    private final JsonCodec json;

    public JreResourceBuilder(String root) {
        this(root, url -> (HttpURLConnection) new URL(url).openConnection(), new GsonCodec());
    }

    public JreResourceBuilder(String root, ConnectionFactory factory, JsonCodec codec) {
        this.factory = factory;
        this.json = codec;
        path(root);
    }

    private String encode(String key) {
        try { return URLEncoder.encode(key, "UTF-8"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String query() {
        String q = "";
        for (Param p : expand(queryParams)) q += (q.isEmpty() ? "" : "&") + encode(p.k) + "=" + encode(p.v.toString());
        return q.isEmpty() ? "" : "?" + q;
    }

    private String uri() {
        return paths.stream().collect(joining()) + query();
    }

    @Override public <T> T as(Class<? super T> container, Class<?> type) {
        return json.<T>fromJson(request(), container, type);
    }

    private Single<Reader> request() {
        return Single.using(() -> {
            HttpURLConnection req;
            try {
                req = factory.apply(uri());
                req.setRequestMethod(method);
                if (produces.length > 0) req.setRequestProperty("Accept", produces[0]);
                if (consumes.length > 0) req.setRequestProperty("Content-Type", consumes[0]);
                for (Param e : headerParams) req.setRequestProperty(e.k, Objects.toString(e.v));
            } catch (Exception e) {
                throw err("open connection error", e);
            }
            if (data != null) {
                req.setDoOutput(true);
                try (OutputStreamWriter out = new OutputStreamWriter(req.getOutputStream())) {
                    json.toJson(data, out);
                } catch (Exception e) {
                    throw err("writing stream error", e);
                }
            }
            Reader reader;
            try {
                reader = new InputStreamReader(req.getInputStream());
                int rc = req.getResponseCode();
                if (rc != 200 && rc != 201 && rc != 204) {
                    throw new RuntimeException("unexpected response code " + rc);
                }
            } catch (IOException e) {
                throw err("reading stream error", e);
            }
            return reader;
        }, Single::just, reader -> {
            try { reader.close(); } catch (IOException e) { throw err("closing response error", e); }
        });
    }

    private static RuntimeException err(String msg, Exception e) { return new RuntimeException(msg + ": " + e, e); }

    @FunctionalInterface
    public interface ConnectionFactory {
        HttpURLConnection apply(String s) throws Exception;
    }

    public interface JsonCodec {
        void toJson(Object src, Appendable writer);
        <C> C fromJson(Single<Reader> json, Class<? super C> container, Class<?> type);
    }

    public static class GsonCodec implements JsonCodec {
        private final Gson gson = new Gson();

        @Override public void toJson(Object src, Appendable writer) {
            gson.toJson(src, writer);
        }

        @SuppressWarnings("unchecked")
        @Override public <T> T fromJson(Single<Reader> req, Class<? super T> container, Class<?> type) {
            if (Completable.class.equals(container)) return (T) req.doOnSuccess(this::consume).toCompletable();
            if (Single.class.equals(container)) return (T) req.map(reader -> gson.fromJson(reader, type));
            if (Observable.class.equals(container)) return (T) req.toObservable()
                    .flatMapIterable(n -> () -> new ParseArrayIterator<>(n, type));
            throw new IllegalArgumentException("unsupported type " + container);
        }

        private class ParseArrayIterator<T> implements Iterator<T> {
            private final Class<T> type;
            private JsonReader reader;
            public ParseArrayIterator(Reader reader, Class<T> type) {
                this.type = type;
                this.reader = new JsonReader(reader);
                try { this.reader.beginArray(); } catch (Exception e) { throw err("parsing error", e); }
            }
            @Override public boolean hasNext() {
                try {
                    return reader != null && reader.hasNext();
                } catch (Exception e) { throw err("parsing error", e); }
            }
            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    T next = gson.fromJson(reader, type);
                    if (!reader.hasNext()) { reader.endArray(); reader.close(); reader = null; }
                    return next;
                } catch (Exception e) { throw err("parsing error", e); }
            }
        }

        /** Consume network buffer, some SO might have problems if not. */
        private void consume(Reader n) { try { while (n.read() != -1) ; } catch (IOException ignore) {/**/} }
    }
}
