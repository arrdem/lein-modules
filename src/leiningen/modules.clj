(ns leiningen.modules
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [lein-modules
             [inheritance :refer [inherit]]
             [common :refer [parent with-profiles read-project]]
             [compression :refer [compressed-profiles]]]
            [leiningen.core
             [project :as prj]
             [main :as main]
             [eval :as eval]
             [utils :as utils]])
  (:import [java.io File PushbackReader]))

(defn child?
  "Return true if child is an immediate descendant of project"
  [project child]
  (= (:root project) (:root (parent child))))

(defn file-seq-sans-symlinks
  "A tree seq on java.io.Files that aren't symlinks"
  [dir]
  (tree-seq
    (fn [^java.io.File f] (and (.isDirectory f) (not (utils/symlink? f))))
    (fn [^java.io.File d] (seq (.listFiles d)))
    dir))

(defn children
  "Return the child maps for a project according to its active profiles"
  [project]
  (if-let [dirs (-> project :modules :dirs)]
    (remove nil?
      (map (comp #(try (read-project %) (catch Exception e (println (.getMessage e))))
             (memfn getCanonicalPath)
             #(io/file (:root project) % "project.clj"))
        dirs))
    (->> (file-seq-sans-symlinks (io/file (:root project)))
      (filter #(= "project.clj" (.getName %)))
      (remove #(= (:root project) (.getParent %)))
      (map (comp #(try (read-project %) (catch Exception e (println (.getMessage e)))) str))
      (remove nil?)
      (filter #(child? project (with-profiles % (compressed-profiles project)))))))

(defn id
  "Returns fully-qualified symbol identifier for project"
  [project]
  (if project
    (symbol (:group project) (:name project))))

(defn progeny
  "Recursively return the project's children in a map keyed by id"
  ([project]
     (progeny project (compressed-profiles project)))
  ([project profiles]
     (let [kids (children (with-profiles project profiles))]
       (apply merge
         (into {} (map (juxt id identity) kids))
         (->> kids
           (remove #(= (:root project) (:root %))) ; in case "." in :dirs
           (map #(progeny % profiles)))))))

(defn interdependence
  "Turn a progeny map (symbols to projects) into a mapping of projects
  to their dependent projects"
  [pm]
  (let [deps (fn [p] (->> (:dependencies p)
                      (map first)
                      (map pm)
                      (remove nil?)))]
    (reduce (fn [acc [_ p]] (assoc acc p (deps p))) {} pm)))

(defn topological-sort [deps]
  "A topological sort of a mapping of graph nodes to their edges (credit Jon Harrop)"
  (loop [deps deps, resolved #{}, result []]
    (if (empty? deps)
      result
      (if-let [dep (some (fn [[k v]] (if (empty? (remove resolved v)) k)) deps)]
        (recur (dissoc deps dep) (conj resolved dep) (conj result dep))
        (throw (Exception. (apply str "Cyclic dependency: " (interpose ", " (map :name (keys deps))))))))))

(def ordered-builds
  "Sort a representation of interdependent projects topologically"
  (comp topological-sort interdependence progeny))

(defn create-checkouts
  "Create checkout symlinks for interdependent projects"
  [projects]
  (doseq [[project deps] projects]
    (when-not (empty? deps)
      (let [dir (io/file (:root project) "checkouts")]
        (when-not (.exists dir)
          (.mkdir dir))
        (println "Checkouts for" (:name project))
        (binding [eval/*dir* dir]
          (doseq [dep deps]
            (eval/sh "rm" "-f" (:name dep))
            (eval/sh "ln" "-sv" (:root dep) (:name dep))))))))

(def checkout-dependencies
  "Setup checkouts/ for a project and its interdependent children"
  (comp create-checkouts interdependence progeny))

(defn cli-with-profiles
  "Set the profiles in the args unless some are already there"
  [profiles args]
  (if (some #{"with-profile" "with-profiles"} args)
    args
    (with-meta (concat
                 ["with-profile" (->> profiles
                                   (map name)
                                   (interpose ",")
                                   (apply str))]
                 args)
      {:profiles-added true})))

(defn dump-profiles
  [args]
  (if (-> args meta :profiles-added)
    (str "(" (second args) ")")
    ""))

(defn dump-modules
  [modules]
  (if (empty? modules)
    (println "No modules found")
    (do
      (println " Module build order:")
      (doseq [p modules]
        (println "  " (:name p)))
      (map id modules))))

(defn modules
  "Run a task for all related projects in dependency order.

  Any task (along with any arguments) will be run in this project and
  then each of this project's child modules. For example:

  $ lein modules install
  $ lein modules deps :tree
  $ lein modules do clean, test
  $ lein modules analias

  You can create 'checkout dependencies' for all interdependent modules
  by including the :checkouts flag:

  $ lein modules :checkouts

  And you can limit which modules run the task with the :dirs option:

  $ lein modules :dirs core,web install

  Delimited by either comma or colon, this list of relative paths
  will override the [:modules :dirs] config in project.clj"
  [project & args]
  (println "DEBUG]" args)
  (condp = (first args)
    ":checkouts" (do
                   (checkout-dependencies project)
                   (apply modules project (remove #{":checkouts"} args)))
    ":dirs"      (let [dirs (s/split (second args) #"[:,]")]
                   (apply modules
                          (-> project
                              (assoc-in [:modules :dirs] dirs)
                              (vary-meta assoc-in [:without-profiles :modules :dirs] dirs))
                          (drop 2 args)))
    ":parent"    (let [[_ parentf next-task & args] args
                       parent                       (-> (io/file parentf)
                                                        (io/reader)
                                                        (PushbackReader.)
                                                        read)
                       project                      (assoc-in project [:modules :parent] parent)]
                   (main/apply-task next-task project args)) 
    nil          (dump-modules (ordered-builds project))
    (let [modules             (ordered-builds project)
          profiles            (compressed-profiles project)
          args                (cli-with-profiles profiles args)
          subprocess          (get-in project [:modules :subprocess]
                                      (or (System/getenv "LEIN_CMD")
                                          (if (= :windows (utils/get-os)) "lein.bat" "lein")))
          parent-project-file (File/createTempFile "project" ".edn")]
      (spit parent-project-file (pr-str project))
      (dump-modules modules)

      (doseq [subproject modules]
        ;; Print the banner. Note that the printed version may be wrong due to plugins not having run yet.
        (println "------------------------------------------------------------------------")
        (println " Building" (:name subproject) (:version subproject) (dump-profiles args))
        (println "------------------------------------------------------------------------")

        (if-let [cmd (get-in subproject [:modules :subprocess] subprocess)]
          ;; If we're using subprocesses
          (binding [eval/*dir* (:root subproject)]
            (let [exit-code (apply eval/sh cmd "modules" ":parent" (.getPath parent-project-file) args)]
              (when (pos? exit-code)
                (throw (ex-info "Subprocess failed" {:exit-code exit-code})))))

          ;; Otherwise try to run the task in this process
          (let [subproject (prj/init-project subproject)
                task       (main/lookup-alias (first args) subproject)]
            (main/apply-task task subproject (rest args))))))))
