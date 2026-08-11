(ns jj.potoroo.httpclient
  "A thin, idiomatic Clojure wrapper around the JDK's built-in HTTP client"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import (java.io InputStream)
           (java.net URI)
           (java.net.http HttpClient
                          HttpClient$Redirect
                          HttpClient$Version
                          HttpRequest
                          HttpRequest$BodyPublisher
                          HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset Charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defprotocol RequestTransformer
  "Turns a request into a java.net.http BodyPublisher.

  Chosen per-request with :body-as, the way :as chooses a ResponseTransformer.
  Receives the whole request map, not just :body, so a transformer can also
  look at :headers - a multipart one, say, reading its boundary off the
  caller's content-type."
  (->request [this req]))

(def default-request-transformer
  "Used when a request supplies no :body-as.

  Handles the body types the JDK client already understands: nil (no body),
  String, byte[], and an already-built BodyPublisher. Anything else is a
  caller error - reach for a transformer that knows how to serialize it."
  (reify RequestTransformer
    (->request [_ {:keys [body]}]
      (cond
        (nil? body)    (HttpRequest$BodyPublishers/noBody)
        (string? body) (HttpRequest$BodyPublishers/ofString body)
        (bytes? body)  (HttpRequest$BodyPublishers/ofByteArray body)
        (instance? HttpRequest$BodyPublisher body) body
        :else (throw (ex-info "Unsupported request body type"
                              {:body body :type (type body)}))))))

(defn charset-of
  "The charset advertised by a response's content-type headers, UTF-8 if absent.
  Public so a ResponseTransformer can decode text the way `as-string` does."
  ^Charset [headers]
  (if-let [cs (some->> (clojure.core/get headers "content-type")
                       (re-find #"(?i)charset=([^;\s]+)")
                       second)]
    (Charset/forName cs)
    StandardCharsets/UTF_8))

(defprotocol ResponseTransformer
  "Turns the raw response body into the value put on :body.

  Chosen per-request with :as. Receives the whole response map - :status,
  :headers, and :body holding the raw InputStream - so a transformer can branch
  on the status or read a header the charset alone would not tell it (a
  multipart boundary, say); `charset-of` derives the charset from :headers.
  Implementations that consume the stream must close it."
  (->response [this resp]))

(def as-string
  "Reads the whole body and decodes it with the response's charset."
  (reify ResponseTransformer
    (->response [_ {:keys [headers body]}]
      (with-open [^InputStream in body]
        (String. (.readAllBytes in) ^Charset (charset-of headers))))))

(def as-stream
  "Hands back the raw InputStream. The caller owns it, and must close it."
  (reify ResponseTransformer
    (->response [_ {:keys [body]}]
      body)))

(def as-bytes
  "Reads the whole body into a byte array."
  (reify ResponseTransformer
    (->response [_ {:keys [body]}]
      (with-open [^InputStream in body]
        (.readAllBytes in)))))


(defn client
  "Build a java.net.http.HttpClient.

  Options:
    :connect-timeout-ms  connection timeout in milliseconds (default: none)
    :follow-redirects    :always | :normal | :never (default: :normal)
    :version             :http-1.1 | :http-2 (default: :http-2)"
  (^HttpClient [] (client {}))
  (^HttpClient [{:keys [connect-timeout-ms follow-redirects version]}]
   (let [b (HttpClient/newBuilder)]
     (when connect-timeout-ms
       (.connectTimeout b (Duration/ofMillis connect-timeout-ms)))
     (.followRedirects b (case follow-redirects
                           :always HttpClient$Redirect/ALWAYS
                           :never  HttpClient$Redirect/NEVER
                           HttpClient$Redirect/NORMAL))
     (.version b (case version
                   :http-1.1 HttpClient$Version/HTTP_1_1
                   HttpClient$Version/HTTP_2))
     (.build b))))

(def ^:private ^HttpClient default-client
  "A shared client used when none is supplied to `request`."
  (client))

(defn- build-request
  ^HttpRequest [{:keys [method url headers timeout-ms body-as] :as req}]
  (let [^HttpRequest$BodyPublisher publisher
        (->request (or body-as default-request-transformer) req)
        b (-> (HttpRequest/newBuilder)
              (.uri (URI/create url))
              (.method (-> method name str/upper-case) publisher))]
    (doseq [[k v] headers]
      (.header b (name k) (str v)))
    (when timeout-ms
      (.timeout b (Duration/ofMillis timeout-ms)))
    (.build b)))

(defn- ->headers-map
  [^HttpResponse resp]
  (into {}
        (map (fn [[k vs]] [k (str/join ", " vs)]))
        (.map (.headers resp))))

(defn request
  [{:keys [client as] :as req}]
  (let [^HttpClient c   (or client default-client)
        transformer     (or as as-string)
        ^HttpResponse r (.send c
                               (build-request req)
                               (HttpResponse$BodyHandlers/ofInputStream))
        resp            {:status  (.statusCode r)
                         :headers (->headers-map r)
                         :body    (.body r)}]
    (assoc resp :body (->response transformer resp))))

(defn- verb [method url opts]
  (request (assoc opts :method method :url url)))

(defn get    [url & [opts]] (verb :get    url opts))
(defn post   [url & [opts]] (verb :post   url opts))
(defn put    [url & [opts]] (verb :put    url opts))
(defn patch  [url & [opts]] (verb :patch  url opts))
(defn delete [url & [opts]] (verb :delete url opts))
(defn head   [url & [opts]] (verb :head   url opts))
(defn query   [url & [opts]] (verb :query   url opts))
