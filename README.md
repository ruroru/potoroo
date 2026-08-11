# potoroo

A thin, idiomatic Clojure wrapper around the JDK's built-in HTTP client
(`java.net.http.HttpClient`).

## Installation

Requires Java 11+.

```clojure
[org.clojars.jj/potoroo "0.1.0-SNAPSHOT"]
```

## Quick start

```clojure
(require '[jj.potoroo.httpclient :as http])

(http/get "http://api.example.com/hello")
;; => {:status 200
;;     :headers {"content-type" "text/plain", ...}
;;     :body "hello world"}

(http/post "http://api.example.com/things"
           {:headers {"content-type" "application/json"}
            :body    "{\"name\":\"widget\"}"})
```

Verbs: `get` `post` `put` `patch` `delete` `head` `query` (HTTP QUERY — a safe,
idempotent GET-with-a-body). Each takes a URL and an optional options map.
`request` is the same thing as a single map:

```clojure
(http/request {:method :get :url "http://api.example.com/hello"})
```

## The response map

Always exactly three keys: `:status` (an `int` — non-2xx is **returned, not
thrown**, so branch on it rather than catching), `:headers` (`{String String}`,
repeated headers comma-joined per RFC 7230), and `:body` (whatever `:as`
produced — a `String` by default).

## Request options

| Key           | Meaning                                                              |
|---------------|----------------------------------------------------------------------|
| `:method`     | Keyword or string, e.g. `:get` `:post` (only for `request`).         |
| `:url`        | Target URL (only for `request`; the verbs take it positionally).     |
| `:headers`    | Map of header name → value.                                          |
| `:body`       | `nil`, `String`, `byte[]`, or a `BodyPublisher`; anything else needs `:body-as`. |
| `:timeout-ms` | Per-request timeout in milliseconds.                                 |
| `:client`     | An `HttpClient` (defaults to a shared one).                          |
| `:body-as`    | Turns `:body` into a `BodyPublisher`. Only needed for body types not listed above. |
| `:as`         | Turns the response body into `:body`. Omit it to get a `String`.     |

## Response bodies (`:as`)

The body arrives as an `InputStream` and is handed to the `:as` transformer.

| `:as`                      | `:body` you get                              | Stream                                        |
|----------------------------|----------------------------------------------|-----------------------------------------------|
| `as-string` (default)      | `String`, decoded with the response charset  | read and closed for you                       |
| `as-bytes`                 | `byte[]`                                     | read and closed for you                       |
| `as-stream`                | the raw `InputStream`                        | **yours to close** — closing frees the connection |

The default reads the whole body into memory; for large or binary responses use
`as-stream`:

```clojure
(let [{:keys [body]} (http/get "http://api.example.com/big" {:as http/as-stream})]
  (with-open [in body]
    ;; ... consume in ...
    ))
```

## Transformers

Both sides are protocols you can implement, and each is handed the **whole map**
for its side.

`ResponseTransformer` receives `{:status :headers :body}` with `:body` holding
the raw stream, and returns the value that replaces it. Implementations that do
not return the stream itself must close it; `http/charset-of` derives the
charset from `:headers` the way `as-string` does.

```clojure
(defrecord UpperCase []
  http/ResponseTransformer
  (->response [_ {:keys [body]}]
    (with-open [in body]
      (clojure.string/upper-case (slurp in)))))

(http/get "http://api.example.com/hello" {:as (->UpperCase)})
```

`RequestTransformer` receives the request map and returns a `BodyPublisher`.
The default, `default-request-transformer`, handles `nil`, `String`, `byte[]`,
and a ready-made `BodyPublisher` (passed through untouched); anything else
throws `ex-info` with `"Unsupported request body type"`.

```clojure
(defrecord EdnBody []
  http/RequestTransformer
  (->request [_ {:keys [body]}]
    (java.net.http.HttpRequest$BodyPublishers/ofString (pr-str body))))

(http/post "http://api.example.com/things"
           {:body {:name "widget"} :body-as (->EdnBody)})
```

## Building a client

```clojure
(def c (http/client {:connect-timeout-ms 1000
                     :follow-redirects   :never      ; :always | :normal | :never
                     :version            :http-1.1})) ; :http-1.1 | :http-2

(http/get "http://api.example.com/hello" {:client c})
```

## Development

```bash
lein test
```

## License

Copyright © 2025 [ruroru](https://github.com/ruroru)

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0/.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
