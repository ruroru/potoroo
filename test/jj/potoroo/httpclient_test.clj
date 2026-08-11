(ns jj.potoroo.httpclient-test

  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [jj.potoroo.httpclient :as http]
            [ring-http-exchange.core :as ring])
  (:import (com.sun.net.httpserver HttpServer)
           (java.io ByteArrayOutputStream InputStream)
           (java.net ServerSocket)
           (java.net.http HttpRequest$BodyPublishers)))

(def ^:dynamic *port* nil)

(defn- slurp-body
  "Ring bodies here are always strings, but read defensively."
  [body]
  (if (instance? InputStream body)
    (with-open [in ^InputStream body
                out (ByteArrayOutputStream.)]
      (.transferTo in out)
      (.toString out "UTF-8"))
    (str body)))

(defn echo-handler
  "Echoes the request back so tests can assert on method/headers/body."
  [{:keys [request-method uri headers body] :as _req}]
  (cond
    (= uri "/hello")
    {:status 200
     :headers {"content-type" "text/plain"
               "x-custom" "abc"}
     :body "hello world"}

    (= uri "/echo")
    {:status 200
     :headers {"content-type" "text/plain"
               "x-method" (-> request-method name str/upper-case)
               "x-seen-header" (get headers "X-test" "")}
     :body (slurp-body body)}

    (= uri "/boom")
    {:status 500
     :headers {"content-type" "text/plain"}
     :body "kaboom"}

    :else
    {:status 404 :headers {} :body "not found"}))

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn server-fixture [test-fn]
  (let [port (free-port)
        ^HttpServer server (ring/run-http-server echo-handler {:port port
                                                               :host "localhost"})]
    (try
      (binding [*port* port]
        (test-fn))
      (finally
        (ring/stop-http-server server 0)))))

(use-fixtures :each server-fixture)

(defn- url [path] (str "http://localhost:" *port* path))

(deftest default-body-is-a-string
  (testing "with no :as, the body is a String read and closed for you"
    (let [{:keys [status body]} (http/get (url "/hello"))]
      (is (= 200 status))
      (is (string? body))
      (is (= "hello world" body)))))

(deftest stream-coercer-is-opt-in
  (testing "with :as as-stream, the body is the raw InputStream (caller owns it)"
    (let [{:keys [status body]} (http/get (url "/hello") {:as http/as-stream})]
      (is (= 200 status))
      (is (instance? InputStream body))
      (is (= "hello world" (slurp-body body))))))

(deftest get-request
  (testing "GET with :as as-string returns status, headers and a String body"
    (let [{:keys [status headers body]} (http/get (url "/hello") {:as http/as-string})]
      (is (= 200 status))
      (is (= "hello world" body))
      (is (= "abc" (get headers "x-custom")))
      (is (str/starts-with? (get headers "content-type") "text/plain")))))

(deftest post-with-body-and-headers
  (testing "POST sends body and custom headers, which the server echoes back"
    (let [{:keys [status headers body]}
          (http/post (url "/echo")
                     {:headers {"x-test" "from-client"}
                      :body "payload-123"
                      :as http/as-string})]
      (is (= 200 status))
      (is (= "payload-123" body))
      (is (= "POST" (get headers "x-method")))
      (is (= "from-client" (get headers "x-seen-header"))))))

(deftest verbs-carry-through
  (testing "each convenience verb sends the right HTTP method"
    (doseq [[verb-fn expected] [[http/put "PUT"]
                                [http/patch "PATCH"]
                                [http/delete "DELETE"]]]
      (let [{:keys [headers]} (verb-fn (url "/echo") {:body "x" :as http/as-string})]
        (is (= expected (get headers "x-method")))))))

(deftest head-sends-head-method
  (testing "HEAD reaches the server as the HEAD method and returns a status"
    (let [{:keys [status headers]} (http/head (url "/echo") {:as http/as-string})]
      (is (= 200 status))
      (is (= "HEAD" (get headers "x-method"))))))

(deftest query-sends-query-method-with-body
  (testing "QUERY sends its body and reaches the server as the QUERY method"
    (let [{:keys [status headers body]}
          (http/query (url "/echo") {:body "find-me" :as http/as-string})]
      (is (= 200 status))
      (is (= "QUERY" (get headers "x-method")))
      (is (= "find-me" body)))))

(deftest non-2xx-is-returned-not-thrown
  (testing "5xx responses come back as data, not exceptions"
    (let [{:keys [status body]} (http/get (url "/boom") {:as http/as-string})]
      (is (= 500 status))
      (is (= "kaboom" body)))))

(deftest byte-array-response-coercer
  (testing "as-bytes reads the whole body into a byte array"
    (let [{:keys [status body]} (http/get (url "/hello") {:as http/as-bytes})]
      (is (= 200 status))
      (is (bytes? body))
      (is (= "hello world" (String. ^bytes body "UTF-8"))))))

(deftest byte-array-request-body
  (testing "a byte[] request body is accepted"
    (let [{:keys [status body]}
          (http/post (url "/echo") {:body (.getBytes "raw-bytes" "UTF-8")
                                    :as http/as-string})]
      (is (= 200 status))
      (is (= "raw-bytes" body)))))

(deftest unsupported-request-body-is-rejected
  (testing "the default request transformer refuses a body it cannot publish"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported request body type"
                          (http/post (url "/echo") {:body {:a 1}})))))

(defrecord EdnBody []
  http/RequestTransformer
  (->request [_ {:keys [body]}]
    (HttpRequest$BodyPublishers/ofString (pr-str body))))

(deftest custom-request-transformer-via-body-as
  (testing ":body-as serializes a body the default transformer would reject"
    (let [{:keys [status body]}
          (http/post (url "/echo") {:body {:a 1}
                                    :body-as (->EdnBody)
                                    :as http/as-string})]
      (is (= 200 status))
      (is (= {:a 1} (read-string body))))))

(defrecord UpperCase []
  http/ResponseTransformer
  (->response [_ {:keys [body]}]
    (with-open [in ^InputStream body]
      (-> (slurp-body in) str/upper-case))))

(deftest custom-coercer-via-protocol
  (testing "a user coercer added by extending ResponseTransformer is used"
    (let [{:keys [status body]} (http/get (url "/hello") {:as (->UpperCase)})]
      (is (= 200 status))
      (is (= "HELLO WORLD" body)))))

(deftest explicit-client
  (testing "a caller-supplied client is used"
    (let [c (http/client {:connect-timeout-ms 1000 :follow-redirects :never})
          {:keys [status]} (http/request {:method :get
                                          :url (url "/hello")
                                          :client c})]
      (is (= 200 status)))))

(deftest explicit-client
  (testing "a caller-supplied client is used"
    (let [c (http/client {:connect-timeout-ms 1000 :follow-redirects :never})
          {:keys [status]} (http/request {:method :get
                                          :url (url "/hello")
                                          :client c})]
      (is (= 200 status)))))
