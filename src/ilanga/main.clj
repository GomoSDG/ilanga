(ns ilanga.main
  (:gen-class))

(defn -main [& _args]
  (println "Ilanga — starting")
  ;; TODO: start runtime components (TDD-07)
  ;;   - load global config (hardware descriptors, device registry)
  ;;   - start TCP ingest server (Aleph)
  ;;   - start engine pipeline (core.async)
  ;;   - start :tick timer
  )
