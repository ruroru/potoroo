(defproject org.clojars.jj/potoroo "0.1.0-SNAPSHOT"
  :description "A thin, idiomatic Clojure wrapper around HttpClient"
  :url "http://example.com/potoroo"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :profiles {:test {:dependencies [[org.clojars.jj/ring-http-exchange "1.4.9"]]}}
  :repl-options {:init-ns jj.potoroo.httpclient}


  :deploy-repositories [["clojars" {:url      "https://repo.clojars.org"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]]

  :plugins [[org.clojars.jj/bump "1.0.4"]
            [org.clojars.jj/bump-md "1.1.0"]
            [org.clojars.jj/lein-git-tag "1.0.1"]
            [org.clojars.jj/strict-check "1.1.0"]]
  )
