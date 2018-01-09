(defproject me.arrdem/lein-modules "0.3.12-SNAPSHOT"
  :description "Similar to Maven multi-module projects, but less sucky"
  :url "https://github.com/arrdem/lein-modules"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true

  :plugins [[me.arrdem/lein-git-version "2.0.4"]]
  :git-version {:status-to-version
                (fn [{:keys [tag version branch ahead ahead? dirty?] :as git}]
                  (assert (re-find #"\d+\.\d+\.\d+" tag)
                          "Tag is assumed to be a raw SemVer version")
                  (if (and tag (not ahead?) (not dirty?))
                    tag
                    (let [[_ prefix patch] (re-find #"(\d+\.\d+)\.(\d+)" tag)
                          patch            (Long/parseLong patch)
                          patch+           (inc patch)]
                      (format "%s.%d-%s-SNAPSHOT" prefix patch+ branch))))
                }

  :deploy-repositories {"releases" :clojars})
