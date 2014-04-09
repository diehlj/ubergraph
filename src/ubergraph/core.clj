(ns ubergraph.core
  (:require [ubergraph.protocols :as up]))

; This namespace provides a concrete implementation of ubergraph.protocols, which is
; a conservative extension to Loom's protocols.

; It supports undirected, directed, weighted, attributes, editing, mixed directedness,
; and multiple edges between a given pair of vertices.

; If Loom adopts the protocol extensions proposed in ubergraph.protocols, then
; this data structure will be compatible with all loom algorithms.

; At the bottom of this file, I demonstrate how one can use Ubergraph to implement
; all the graph types provided by Loom (except Flygraph).  There are a couple
; subtle implementation details that are different between the Ubergraph implementation
; and the Loom default implementations of the protocols.

; 1. Ubergraph-based undirected graphs are designed to return only one direction of an
; undirected edge in the sequence returned by (edges g).  That's because in undirected
; algorithms, you usually want to consider each undirected edge only once.  You can
; retrieve the other direction, if desired, using the new UndirectedGraph protocol.
;
; 2. Edge constructors (such as add-edges) support either [src dest], or [src dest weight]
;    or [src dest attribute-map].
;   
; 3. By default, edges added with the [src dest weight] constructor simply store the weight
;    as a :weight attribute.  This is simply an implementation detail which can be completely
;    ignored if you don't have any attributes and simply want to use the weight protocol
;    to retrieve the weight of an edge.  But by making it an attribute, it has the
;    added benefit that you can alter the weight of an edge using the attribute protocol.
;  
; 4. The edges that are returned by edges are not simple vectors, they are a custom Edge
;    data structure.  All the functions that consume edges can take this custom Edge 
;    data structure, or simpler forms like [src dest] if that is enough to uniquely
;    identify the edge.  It is recommended that edge-processing algorithms access the
;    source and destination nodes using the new Edge protocol (src and dest), rather
;    than assuming that an edge is a vector.
; 
; 5. The build-graph semantics are somewhat different from Loom's. Since Ubergraphs
;    are capable of holding both directed and undirected edges, if you build a
;    directed graph from an undirected graph, those edges are imported as undirected,
;    and conversely, if you build an undirected graph from a directed graph, those edges
;    are imported as directed.


; These are the functions that are too lengthy to define inline
; in the Ubergraph record.
(declare transpose get-edge find-edges add-node add-edge remove-node remove-edge
         edge-description->edge resolve-node-or-edge 
         force-add-directed-edge force-add-undirected-edge remove-edges)

(defrecord Ubergraph [node-map allow-parallel? undirected? attrs reverse-edges]
  up/Graph
  (nodes [g] (keys (:node-map g)))
  ; I've chosen to implement the edges protocol for Ubergraph such that
  ; undirected edges only appear once in the sequence of edges (one of the two directions,
  ; not both).
  (edges [g] (for [[node node-info] (:node-map g)
                   [dest edges] (:out-edges node-info),
                   edge edges
                   :when (not (:duplicate edge))]
               edge))
  (has-node? [g node] (boolean (get-in g [:node-map node])))
  (has-edge? [g n1 n2] (boolean (seq (find-edges g n1 n2))))
  (successors [g] (partial up/successors g))
  (successors [g node] (distinct (map up/dest (up/out-edges g node))))
  (out-degree [g node] (get-in g [:node-map node :out-degree]))
  (out-edges [g] (partial up/out-edges g)) 
  (out-edges [g node] (apply concat (vals (get-in g [:node-map node :out-edges]))))
  
  up/Digraph
  (predecessors [g] (partial up/predecessors g))
  (predecessors [g node] (map up/src (up/in-edges g node)))
  (in-degree [g node] (get-in g [:node-map node :in-degree]))
  (in-edges [g] (partial up/in-edges g))
  (in-edges [g node] (apply concat (vals (get-in g [:node-map node :in-edges]))))
  (transpose [g] (transpose g))
  
  up/WeightedGraph
  ; Ubergraphs by default store weight in an attribute :weight
  ; Using an attribute allows us to modify the weight with the AttrGraph protocol
  (weight [g] (partial up/weight g))
  (weight [g e] (get-in g [:attrs (edge-description->edge g e) :weight] 1))
  (weight [g n1 n2] (get-in g [:attrs (get-edge g n1 n2) :weight] 1))
  
  up/EditableGraph
  (add-nodes* [g nodes] (reduce add-node g nodes))
  ; edge definition should be [src dest] or [src dest weight] or [src dest attribute-map]
  (add-edges* [g edge-definitions] (reduce (fn [g edge] (add-edge g edge)) g edge-definitions)) 
  (remove-nodes* [g nodes] (reduce remove-node g nodes))
  (remove-edges* [g edges] (reduce remove-edge g edges))
  (remove-all [g] (Ubergraph. {} allow-parallel? undirected? {} {}))
  
  up/AttrGraph
  (add-attr [g node-or-edge k v] 
    (assoc-in g [:attrs (resolve-node-or-edge g node-or-edge) k] v))
  (add-attr [g n1 n2 k v] (up/add-attr g (get-edge g n1 n2) k v))
  (remove-attr [g node-or-edge k] 
    (update-in g [:attrs (resolve-node-or-edge g node-or-edge)] dissoc k))
  (remove-attr [g n1 n2 k] (up/remove-attr g (get-edge g n1 n2) k))
  (attr [g node-or-edge k] 
    (get-in g [:attrs (resolve-node-or-edge g node-or-edge) k]))
  (attr [g n1 n2 k] (up/attr g (get-edge g n1 n2) k))
  (attrs [g node-or-edge] 
    (get-in g [:attrs (resolve-node-or-edge g node-or-edge)] {}))
  (attrs [g n1 n2] (up/attrs g (get-edge g n1 n2)))
  
  up/UndirectedGraph
  (reverse-edge [g edge] (get-in g [:reverse-edges (edge-description->edge g edge)]))
  
  up/QueryableGraph
  (find-edges [g edge-query] (find-edges g edge-query))
  (find-edge [g edge-query] (first (up/find-edges g edge-query)))
  
  up/MixedDirectionGraph
  (add-directed-edges* [g edge-definitions] (reduce (fn [g edge] (force-add-directed-edge g edge))
                                                    g edge-definitions))
  (add-undirected-edges* [g edge-definitions] (reduce (fn [g edge] (force-add-undirected-edge g edge))
                                                      g edge-definitions)))


; A node-id is anything the user wants it to be -- a number, a keyword, a data structure
; An edge is something with a src, a dest, and an id that can be used to look up attributes

; node-map is a {node-id node-info}
; node-info is a {:out-edges {dest-id #{edge}} :in-edges {src-id #{edge}} 
;                 :in-degree number :out-degree number}
; edge is either Edge or UndirectedEdge

(defrecord NodeInfo [out-edges in-edges out-degree in-degree])
(defrecord Edge [id src dest]
  up/Edge
  (src [edge] src)
  (dest [edge] dest))
(defrecord UndirectedEdge [id src dest duplicate]
  up/Edge
  (src [edge] src)
  (dest [edge] dest))

(defn- get-edge [g n1 n2] (first (find-edges g n1 n2)))

(defn edge? [o] (or (instance? Edge o) (instance? UndirectedEdge o)))
(defn undirected-edge? [o] (instance? UndirectedEdge o))

(defn- add-node
  [g node]
  (cond
    (get-in g [:node-map node]) g  ; node already exists
    :else (assoc-in g [:node-map node] (->NodeInfo {} {} 0 0))))

(defn- remove-node
  [g node]
  (-> g
    (up/remove-edges* (up/out-edges g node))
    (up/remove-edges* (up/in-edges g node))    
    (update-in [:node-map] dissoc node)))

(def fconj (fnil conj #{}))
(def finc (fnil inc 0))

(defn- submap? [m1 m2]
  (every? identity (for [[k v] m1] (= (m2 k) v))))

(defn- find-edges 
  ([g src dest]
    (get-in g [:node-map src :out-edges dest]))
  ([g {src :src dest :dest :as attributes}]
    (let [edges
          (cond 
            (and src dest) (get-in g [:node-map src :out-edges dest])
            src (up/out-edges g src)
            dest (up/in-edges g dest)
            :else (partial find-edges g))
          attributes (dissoc attributes :src :dest)]
      (for [edge edges
            :when (submap? attributes (get-in g [:attrs edge]))]
        edge))))

(defn- find-edge [& args]
  (first (apply find-edges args)))

(defn- add-directed-edge [g src dest attributes]
  (let [g (-> g (add-node src) (add-node dest))
        edge-id (java.util.UUID/randomUUID)
        edge (->Edge edge-id src dest)
        new-attrs (if attributes 
                    (assoc (:attrs g) edge attributes)
                    (:attrs g))
        node-map (:node-map g)
        node-map-src (get node-map src)
        node-map-dest (get node-map dest)              
        new-node-map-src (-> node-map-src             
                           (update-in [:out-edges dest] fconj edge)
                           (update-in [:out-degree] finc))
        new-node-map-dest (-> node-map-dest
                            (update-in [:in-edges src] fconj edge)
                            (update-in [:in-degree] finc))
        new-node-map (assoc node-map src new-node-map-src dest new-node-map-dest)]
    (Ubergraph. new-node-map (:allow-parallel? g) (:undirected? g) new-attrs (:reverse-edges g))))

(defn- add-undirected-edge [g src dest attributes]
  (let [g (-> g (add-node src) (add-node dest))
        forward-edge-id (java.util.UUID/randomUUID),
        backward-edge-id (java.util.UUID/randomUUID),
        forward-edge (->UndirectedEdge forward-edge-id src dest false),
        backward-edge (->UndirectedEdge backward-edge-id dest src true)
        new-attrs (if attributes 
                    (assoc (:attrs g) forward-edge attributes backward-edge attributes)
                    (:attrs g))
        node-map (:node-map g)
        node-map-src (get node-map src)
        node-map-dest (get node-map dest)              
        new-node-map-src (-> node-map-src             
                           (update-in [:out-edges dest] fconj forward-edge)
                           (update-in [:in-edges dest] fconj backward-edge)
                           (update-in [:in-degree] finc)
                           (update-in [:out-degree] finc))
        new-node-map-dest (-> node-map-dest
                            (update-in [:out-edges src] fconj backward-edge)
                            (update-in [:in-edges src] fconj forward-edge)
                            (update-in [:in-degree] finc)
                            (update-in [:out-degree] finc))
        new-node-map (assoc node-map src new-node-map-src dest new-node-map-dest)
        new-reverse-edges (assoc (:reverse-edges g) forward-edge backward-edge backward-edge forward-edge)]
    (Ubergraph. new-node-map (:allow-parallel? g) (:undirected? g) new-attrs new-reverse-edges)))

(defn- number->map [n]
  (if (number? n) {:weight n} n))

(defn- add-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (get-edge g src dest))
      (update-in g [:attrs (get-edge g src dest)]
                 merge attributes)
      
      (:undirected? g) (add-undirected-edge g src dest attributes)
      :else (add-directed-edge g src dest attributes))))

(defn- force-add-directed-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (get-edge g src dest))
      (update-in g [:attrs (get-edge g src dest)]
                 merge attributes)
      :else (add-directed-edge g src dest attributes))))

(defn- force-add-undirected-edge
  [g [src dest attributes]]
  (let [attributes (number->map attributes)]
    (cond
      (and (not (:allow-parallel? g)) (or (get-edge g src dest)
                 (get-edge g dest src)))
      (let [new-attrs (merge (up/attrs g src dest) (up/attrs g dest src) attributes)]
        (-> g
          (remove-edges [src dest] [dest src])
          (add-undirected-edge src dest attributes)))
      :else (add-undirected-edge g src dest attributes))))


(defn edge-description->edge 
  "Many ubergraph functions can take either an *edge description* (i.e., [src dest]
[src dest weight] or [src dest attribute-map]) or an actual edge object.  This function
is used to convert edge descriptions into an edge object, or passing through an edge
object unchanged, so regardless of what you pass in, you're guaranteed to get out
an edge object."
  [g ed]
  (cond
    (edge? ed) ed
    (not (vector? ed)) (throw (IllegalArgumentException. 
                                (str "Invalid edge description: " ed)))
    (= (count ed) 2) (find-edge g (ed 0) (ed 1))
    (= (count ed) 3) 
    (cond (number? (ed 2))
          (find-edge g {:src (ed 0), :dest (ed 1), :weight (ed 2)})
          (map? (ed 2))
          (find-edge g (assoc (ed 2) :src (ed 0) :dest (ed 1)))
          :else 
          (throw (IllegalArgumentException. 
                   (str "Invalid edge description: " ed))))))

(defn- resolve-node-or-edge
  "Similar to edge-description->edge in that it converts edge descriptions to edge objects,
but this function also passes nodes through unchanged"
  [g node-or-edge]
  (cond (up/has-node? g node-or-edge)
        node-or-edge
        :else
        (try (edge-description->edge g node-or-edge)
          (catch IllegalArgumentException e
            (throw (IllegalArgumentException. (str "Invalid node or edge description: " node-or-edge)))))))

(defn- remove-edge
  [g edge]
  ; Check whether edge exists before deleting  
  (let [edge (edge-description->edge g edge)
        src (:src edge)
        dest (:dest edge)]
    (if (get-in g [:node-map src :out-edges dest edge])
      (if-let
        [reverse-edge (get-in g [:reverse-edges edge])]
        (-> g
          (update-in [:attrs] dissoc edge reverse-edge)
          (update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :in-edges dest] disj reverse-edge)
          (update-in [:node-map src :in-degree] dec)
          (update-in [:node-map src :out-degree] dec)
          (update-in [:node-map dest :out-edges src] disj reverse-edge)
          (update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-degree] dec)
          (update-in [:node-map dest :out-degree] dec))
        (-> g
          (update-in [:edge-map] dissoc edge)
          (update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :out-degree] dec)
          (update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-degree] dec)))
      g)))

(defn- swap-edge [edge]
  (assoc edge :src (:dest edge) :dest (:src edge)))

(defn- transpose [{:keys [node-map allow-parallel? undirected? attrs reverse-edges]}]
  (let [new-node-map
        (into {} (for [[node {:keys [in-edges out-edges in-degree out-degree]}] node-map
                       :let [new-in-edges (into {} (for [[k v] out-edges] [k (set (map swap-edge v))])),
                             new-out-edges (into {} (for [[k v] in-edges] [k (set (map swap-edge v))]))]]
                   [node (NodeInfo. new-out-edges new-in-edges in-degree out-degree)])),       
        
        new-attrs (into {} (for [[o attr] attrs]
                             (if (edge? o) [(swap-edge o) attr] [o attr])))]
    
    (Ubergraph. new-node-map allow-parallel? undirected? new-attrs reverse-edges)))

(defn add-nodes [g & nodes]
  (up/add-nodes* g nodes))

(defn add-edges [g & edges]
  (up/add-edges* g edges))

(defn remove-nodes [g & nodes]
  (up/remove-nodes* g nodes))

(defn remove-edges [g & edges]
  (up/remove-edges* g edges))

(defn build-graph
  "Builds graphs using nodes and edge descriptions of the form [src dest], 
[src dest weight], or [src dest attribute-map].  Also can build from other
ubergraphs, and from adjacency maps using the same adjacency map notation 
as Loom's build-graph."
  [g & inits]
  (letfn [(build [g init]
                 (cond
                   ;; ubergraph
                   (instance? Ubergraph init)
                   (if (zero? (count (:node-map g)))
                     init
                     (let [new-g (up/add-nodes* g (up/nodes init)),
                           directed-edges (for [e (up/edges init)
                                                :when (not (undirected-edge? e))]
                                            [(up/src e) (up/dest e) (up/attrs init e)])
                           undirected-edges (for [e (up/edges init),
                                                  :when (undirected-edge? e)]
                                              [(up/src e) (up/dest e) (up/attrs init e)])
                           _ (println directed-edges undirected-edges)
                           new-g (up/add-directed-edges* new-g directed-edges)
                           new-g (up/add-undirected-edges* new-g undirected-edges)]
                       new-g))

                   ;; Adjacency map
                   (map? init)
                   (let [es (if (map? (val (first init)))
                              (for [[n nbrs] init
                                    [nbr wt] nbrs]
                                [n nbr wt])
                              (for [[n nbrs] init
                                    nbr nbrs]
                                [n nbr]))]
                     (-> g
                       (up/add-nodes* (keys init))
                       (up/add-edges* es)))
                   
                   ;; edge
                   (and (vector? init) (#{2,3} (count init)))
                   (add-edge g [(init 0) (init 1) (number->map (get init 2))])             
                   ;; node
                   :else (add-node g init)))]
    (reduce build g inits)))

;; All of these graph options can also serve as weighted graphs, just initialize accordingly.

(defn multigraph [& inits]
  (apply build-graph (->Ubergraph {} true true {} {}) inits))

(defn multidigraph [& inits]
  (apply build-graph (->Ubergraph {} true false {} {}) inits))

(defn graph [& inits]
  (apply build-graph (->Ubergraph {} false true {} {}) inits))

(defn digraph [& inits]
  (apply build-graph (->Ubergraph {} false false {} {}) inits))








