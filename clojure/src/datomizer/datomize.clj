(ns datomizer.datomize
  ( :use datomizer.debug
         datomizer.utility
         [datomic.api :as d :only (db q)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema

(defn load-datomizer-schema
  "Load the schema used by datomizer."
  [dbc]
  (load-datoms-from-edn-resource-file dbc "datomizer-schema.edn"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Introspection

(defn ref-type
  "Determine the reference type of an attribute."
  [db key]
  (let [attribute (d/entity db (keyword key))]
    (:ref/type attribute)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage

(def byte-array-class (class (byte-array 1)))

(defn element-value-attribute
  "Datomic attribute to use for element value, based on its type."
  [value]
  (condp instance? value
    java.lang.String :element.value/string
    java.lang.Long :element.value/long
    java.lang.Float :element.value/float
    java.lang.Double :element.value/double
    java.lang.Boolean :element.value/boolean
    java.util.Date :element.value/instant
    clojure.lang.Keyword :element.value/keyword
    java.util.List :element.value/vector
    java.util.Map :element.value/map
    java.math.BigDecimal :element.value/bigdec
    java.math.BigInteger :element.value/bigint
    byte-array-class :element.value/bytes
    ;; :element.value/fn
    ;; :element.value/ref
    (throw (java.lang.IllegalArgumentException. (str "Marshalling not supported for type " (class value))))
    ))


(defn datomize
  "Convert collections to datoms."
  [value & {:keys [partition] :or {partition :db.part/user}}]
  (condp instance? value
      java.util.Map (do
                      (if (empty? value)
                        :ref.map/empty
                        (map (fn [[k, v]]
                               {:db/id (d/tempid partition)
                                :element.map/key k
                                (element-value-attribute v) (datomize v :partition partition)})
                             value)))
      java.util.List (do
                       (if (empty? value)
                         :ref.vector/empty
                         (map (fn [[i, v]]
                                {:db/id (d/tempid partition)
                                 :element.vector/index i
                                 (element-value-attribute v) (datomize v :partition partition)})
                              (zipmap (range) value))))
      value))


(defn construct
  [db data & {:keys [partition] :or {partition :db.part/user}}]
  (apply hash-map (mapcat (fn [[attribute value]]
                       (if (ref-type db attribute)
                         [attribute (datomize value)]
                         [attribute value]))
                          data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Retrieval

(declare decode-elements)

(defn decode
  "Convert a datomized element to a collection [key value] pair"
  [entity element]
  (if (instance? clojure.lang.ILookup element)
    (let [key (or (get element :element.map/key) (get element :element.vector/index))]
      (if key
        (let [value-attribute (first (filter #(re-matches #"^:element.value/.*" (str %)) (keys element)))
              value (value-attribute element)]
          [key (decode-elements entity value-attribute value)])
        element))
    element))

(defn decode-elements
  "Convert datomized collection elements back into a collection."
  [entity key elements]
  (if (set? elements)
    (case elements
      #{:ref.vector/empty} []
      #{:ref.map/empty} {}
      (case (ref-type (.db entity) key)
        (:ref/map :ref.type/map) (apply hash-map (flatten (map #(decode entity %) elements)))
        (:ref/vector :ref.type/vector) (map last (sort-by first (map #(decode entity %) elements)))
        elements))
    elements))

(defn undatomize
  [entity]
  (apply hash-map (mapcat (fn [k] [k (decode-elements entity k (get entity (str k)))]) (conj (keys entity) :db/id))))