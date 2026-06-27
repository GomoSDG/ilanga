(ns ilanga.system
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [integrant.core :as ig]
            [ilanga.db :as db])
  (:import [java.io PushbackReader]))
;; META/RUNTIME — the lifecycle graph, not domain (ADR-030). Names the engine
;; (Integrant, the db adapter) here on purpose: this is the layer that wires
;; adapters together, above the domain ports (ADR-035).

;; --- bootstrap: the root of the root (ADR-027) -------------------------------
;; The few parameters needed before any store exists. Read from
;; resources/bootstrap.edn, overridable via ILANGA_* env vars.

(def ^:private defaults
  {:listen-port 5273
   :duckdb-dir  "data"
   :config-db   "data/config.db"})

(defn- env [name parse-fn]
  (some-> (System/getenv name) (parse-fn)))

(defn bootstrap
  "Read bootstrap.edn and apply ILANGA_* env overrides. Missing file → defaults."
  []
  (let [file (some-> (io/resource "bootstrap.edn") (io/reader) (PushbackReader.))
        base (if file (edn/read file) defaults)]
    (-> base
        (assoc :listen-port (or (env "ILANGA_LISTEN_PORT" #(Integer/parseInt %))
                                (:listen-port base)))
        (assoc :duckdb-dir  (or (env "ILANGA_DUCKDB_DIR" identity)
                                (:duckdb-dir base)))
        (assoc :config-db   (or (env "ILANGA_CONFIG_DB" identity)
                                (:config-db base))))))

;; --- the Integrant graph -----------------------------------------------------
;; Data-as-graph: the component map is plain EDN (matches the descriptors-as-
;; data ethos, ADR-005). Dependency order is explicit — config-ds and the pool
;; are independent leaf components; open-store is NOT a graph component (it's
;; per-connection, called over these by the future TCP component).

(defn config
  "Build the Integrant component map from bootstrap parameters."
  [bootstrap]
  {:ilanga/config-ds   {:path (:config-db bootstrap)}
   :ilanga/duckdb-pool {:dir  (:duckdb-dir bootstrap)}})

(defmethod ig/init-key :ilanga/config-ds [_ {:keys [path]}]
  (db/open-config-ds path))

(defmethod ig/halt-key! :ilanga/config-ds [_ _ds]
  ;; No-op: next.jdbc's URL datasource is a connection factory, not a held-open
  ;; resource — nothing to close (each execute! opens/closes its own connection).
  nil)

(defmethod ig/init-key :ilanga/duckdb-pool [_ {:keys [dir]}]
  (db/->duckdb-pool dir))

(defmethod ig/halt-key! :ilanga/duckdb-pool [_ pool]
  (db/close-all pool))

;; --- start / stop / app ------------------------------------------------------
;; Held in a var for the REPL: integrant.repl/reset re-runs start.

(defonce system nil)

(defn start
  "Start the app components in dependency order. Stores the system in the `system`
   var and returns it. With a bootstrap map argument, uses that instead of the
   loaded/env-overridden bootstrap (for tests injecting temp paths)."
  ([]
   (start (bootstrap)))
  ([boot]
   (let [sys (ig/init (config boot))]
     (alter-var-root #'system (constantly sys))
     sys)))

(defn stop
  "Halt the running system. With no arg, halts the var-held system (and clears
   it); with an explicit system map, halts that one."
  ([]
   (stop @#'system))
  ([sys]
   (when sys (ig/halt! sys))
   (alter-var-root #'system (constantly nil))))

(defn app
  "The plain map open-store takes — decoupled from Integrant keys so the adapter
   is not coupled to the lifecycle lib."
  [sys]
  {:config-ds   (:ilanga/config-ds sys)
   :duckdb-pool (:ilanga/duckdb-pool sys)})