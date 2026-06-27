(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib      'ilanga/ilanga)
(def version  "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir  {:src-dirs  ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis     (b/create-basis {:project "deps.edn"})
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (b/create-basis {:project "deps.edn"})
           :main      'ilanga.main}))
