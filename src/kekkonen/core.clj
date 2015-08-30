(ns kekkonen.core
  (:require [schema.core :as s]
            [plumbing.core :as p]
            [clojure.string :as str]
            [clojure.walk :as w]
            [plumbing.fnk.pfnk :as pfnk])
  (:import [clojure.lang Var Keyword]))

(s/defn ^:private user-meta [v :- Var]
  (-> v meta (dissoc :schema :ns :name :file :column :line :doc)))

(s/defschema Handler
  "Action handler metadata"
  {:fn s/Any
   :name s/Keyword
   :type s/Keyword
   :module s/Keyword
   :user {s/Keyword s/Any}
   :description (s/maybe s/Str)
   :input s/Any
   :output s/Any
   (s/optional-key :source-map) {:line s/Int
                                 :column s/Int
                                 :file s/Str
                                 :ns s/Symbol
                                 :name s/Symbol}
   s/Keyword s/Any})

(defn handler? [x]
  (and (map? x) (:fn x)))

(s/defschema PartialHandler
  "Incomplete handler, used in collecting"
  (dissoc Handler :type :module))

(s/defschema Modules
  "Modules form a tree."
  {s/Keyword s/Any #_(s/either [Handler] (s/recursive #'Modules))})

(s/defschema Kekkonen
  {:modules Modules
   :inject {s/Keyword s/Any}
   s/Keyword s/Any})

(s/defn defnk->handler :- (s/maybe PartialHandler)
  "Converts a defnk into a (Partial)Handler. Returns nil if the given
  var does not contain the defnk :schema metadata"
  [v :- Var]
  (let [{:keys [line column file ns name doc schema]} (meta v)]
    (if schema
      {:fn @v
       :name (keyword name)
       :user (user-meta v)
       :description doc
       :input (pfnk/input-schema @v)
       :output (pfnk/output-schema @v)
       :source-map {:line line
                    :column column
                    :file file
                    :ns (ns-name ns)
                    :name name}})))

(s/defn collect-ns :- [PartialHandler]
  "Collects all public vars from a given namespace, which
  can be transformed by defnk->handler fn (given a Var)."
  [ns]
  ; is this a good idea?
  (require ns)
  (some->> ns
           ns-publics
           (keep (comp defnk->handler val))
           vec))

(s/defn collect-ns-map :- {s/Keyword [PartialHandler]}
  "Collects handlers from modules into a map of module->[Action]"
  [modules :- {s/Keyword s/Symbol}]
  (p/map-vals collect-ns modules))

(s/defn ^:private default-type-resolver [handler]
  (assoc handler :type :function))

(p/defnk create :- Kekkonen
  "Creates a Kekkonen."
  [modules :- Modules
   {type-resolver :- s/Any default-type-resolver}
   {inject :- {s/Keyword s/Any} {}}]
  (let [traverse
        (fn f [x m]
          (p/for-map [[k v] x]
            k (if (vector? v)
                (p/for-map [h v
                            :let [resolved (type-resolver h)
                                  module (->> k (conj m) (map name) (str/join "/") keyword)]]
                  (:name h) (assoc resolved :module module))
                (f v (conj m k)))))]
    {:inject inject
     :modules (traverse modules [])}))

(s/defn ^:private action-kws [path :- s/Keyword]
  (-> path str (subs 1) (str/split #"/") (->> (mapv keyword))))

(s/defn some-handler :- (s/maybe Handler)
  "Returns a handler or nil"
  [kekkonen, action :- s/Keyword]
  (get-in (:modules kekkonen) (action-kws action)))

(s/defn all-handlers :- [Handler]
  "Returns all handlers."
  [kekkonen]
  (let [handlers (atom [])]
    (w/prewalk
      (fn [x]
        (if (handler? x)
          (do (swap! handlers conj x) nil)
          x))
      (:modules kekkonen))
    @handlers))

(s/defn invoke
  "Invokes a action handler with the given context."
  ([kekkonen action]
    (invoke kekkonen action {}))
  ([kekkonen action request]
    (let [handler (some-handler kekkonen action)
          ; context-level overrides of inject!
          context (merge (:inject kekkonen) request)]
      (if-not handler
        (throw (ex-info (str "invalid action " action) {}))
        ((:fn handler) context)))))
