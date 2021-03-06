(ns thi.ng.geom.mesh.io
  (:require
   [thi.ng.geom.core :as g]
   [thi.ng.geom.utils :as gu]
   [thi.ng.geom.vector :as v :refer [vec3]]
   [thi.ng.geom.basicmesh :as bm]
   [thi.ng.geom.triangle :as t]
   [thi.ng.dstruct.streams :as streams]
   [thi.ng.strf.core :as f]
   [clojure.string :as str])
  #?(:clj
     (:import
      [java.io OutputStream InputStream])))

;; Configuration parameters

(def ^:dynamic *precision* 5)

;; Stream wrappers

(def wrapped-output-stream
  "Alias for thi.ng.dstruct.streams/output-stream"
  streams/output-stream)

(def wrapped-input-stream
  "Alias for thi.ng.dstruct.streams/input-stream"
  streams/input-stream)

;; STL

(defn write-stl
  "Writes the given mesh as binary STL to output stream wrapper.
  Assumes mesh already has been tessellated into triangles (i.e. only
  the first 3 vertices of each face are exported). Returns stream
  wrapper."
  [out mesh]
  (let [faces    (g/faces mesh)
        fnormals (g/face-normals mesh false)]
    (streams/skip out 80)
    (streams/write-uint32-le out (count faces))
    (doseq [f faces :let [[a b c :as fv] (g/vertices f mesh)]]
      (streams/write-vec3f-le out (or (get fnormals f) (gu/ortho-normal fv)))
      (streams/write-vec3f-le out a)
      (streams/write-vec3f-le out b)
      (streams/write-vec3f-le out c)
      (streams/write-uint16-le out 0))
    out))

(defn read-stl
  "Reads faces from binary STL input stream wrapper and adds them to
  given (or new) mesh."
  ([in]
   (read-stl in (bm/basic-mesh)))
  ([in mesh]
   (streams/skip in 80)
   (let [numf (streams/read-uint32-le in)
         mesh (if (fn? mesh) (mesh numf) mesh)]
     (loop [mesh mesh, i numf]
       (if (pos? i)
         (let [_ (streams/read-vec3f-le in)
               a (vec3 (streams/read-vec3f-le in))
               b (vec3 (streams/read-vec3f-le in))
               c (vec3 (streams/read-vec3f-le in))]
           (streams/skip in 2)
           (recur (g/add-face mesh [[a b c]]) (dec i)))
         mesh)))))

;; Stanford PLY

(defn write-ply
  "Writes the given mesh as binary PLY to output stream wrapper.
  For compatibility with some external tools, the mesh should already have been
  tessellated before calling this fn. Returns stream wrapper."
  [out mesh]
  (let [vertices    (g/vertices mesh)
        vindex      (zipmap vertices (range))
        vnormals    (g/vertex-normals mesh false)
        faces       (g/faces mesh)
        vnorms?     (not (nil? (seq vnormals)))
        write-props (fn [props]
                      (doseq [p props]
                        (streams/write-utf8-bytes
                         out (str "property float32 " p "\n"))))]
    (-> out
        (streams/write-utf8-bytes "ply\n")
        (streams/write-utf8-bytes "format binary_little_endian 1.0\n")
        (streams/write-utf8-bytes (str "element vertex " (count vertices) "\n")))
    (write-props ['x 'y 'z])
    (when vnorms?
      (write-props ['nx 'ny 'nz]))
    (-> out
        (streams/write-utf8-bytes (str "element face " (count faces) "\n"))
        (streams/write-utf8-bytes "property list uint8 uint32 vertex_indices\n")
        (streams/write-utf8-bytes "end_header\n"))
    (if vnorms?
      (doseq [v vertices]
        (streams/write-vec3f-le out v)
        (streams/write-vec3f-le out (get vnormals v)))
      (doseq [v vertices]
        (streams/write-vec3f-le out v)))
    (doseq [f faces :let [fverts (g/vertices f mesh)]]
      (streams/write-uint8 out (unchecked-byte (count fverts)))
      (doseq [v fverts]
        (streams/write-uint32-le out (get vindex v))))
    out))

;; Wavefront OBJ

(defn- obj-fmt2
  [prefix]
  (let [ff  (f/float *precision*)
        fmt [prefix " " ff " " ff "\n"]]
    #(f/format fmt (double %1) (double %2))))

(defn- obj-fmt3
  [prefix]
  (let [ff  (f/float *precision*)
        fmt [prefix " " ff " " ff " " ff "\n"]]
    #(f/format fmt (double %1) (double %2) (double %3))))

(defn- obj-fmt-face-v
  [verts]
  (str "f " (str/join " " verts) "\n"))

(defn- obj-fmt-face-v-uv
  [verts uvs]
  (str "f " (str/join " " (map #(str % "/" %2) verts uvs)) "\n"))

(defn- obj-fmt-face-v-vn
  [verts normals]
  (str "f " (str/join " " (map #(str % "//" %2) verts normals)) "\n"))

(defn- obj-fmt-face-v-uv-vn
  [verts uvs normals]
  (str "f " (str/join " " (map #(str % "/" %2 "/" %3) verts uvs normals)) "\n"))

(defn write-obj
  "Writes mesh as Waveform OBJ format to output stream wrapper.
  Returns stream wrapper."
  [out mesh]
  (let [vertices    (g/vertices mesh)
        vindex      (zipmap vertices (range))
        vnormals    (g/vertex-normals mesh false)
        vnorms?     (not (nil? (seq vnormals)))
        nindex      (zipmap (vals vnormals) (range))
        faces       (g/faces mesh true)
        fmt-vertex  (obj-fmt3 "v")
        fmt-vnormal (obj-fmt3 "vn")]
    (doseq [[x y z] vertices]
      (streams/write-utf8-bytes
       out (fmt-vertex x y z)))
    (doseq [[x y z] (vals vnormals)]
      (streams/write-utf8-bytes out (fmt-vnormal x y z)))
    (streams/write-utf8-bytes out "g\n")
    (if vnorms?
      (doseq [[fverts] faces]
        (streams/write-utf8-bytes
         out (obj-fmt-face-v-vn
              (map #(inc (get vindex %)) fverts)
              (map #(inc (get nindex (get vnormals %))) fverts))))
      (doseq [[fverts] faces]
        (streams/write-utf8-bytes
         out (obj-fmt-face-v
              (map #(inc (get vindex %)) fverts)))))
    out))

(defn write-obj-indexed
  "Writes IndexedMesh as Waveform OBJ format to output stream wrapper.
  Suports vertex normals & UV coordinates. Returns stream wrapper."
  ([out mesh]
   (write-obj-indexed out mesh {}))
  ([out mesh opts]
   (let [vertices    (-> mesh :vertices :id->v)
         vindex      (-> mesh :vertices :v->id)
         uvs         (-> mesh :attribs :uv :id->v)
         vnormals    (-> mesh :attribs :vnormals :id->v)
         faces       (-> mesh :faces)
         fmt-vertex  (obj-fmt3 "v")
         fmt-vnormal (obj-fmt3 "vn")
         fmt-uv      (obj-fmt2 "vt")]
     (when-let [mtl (get opts :mtl-lib)]
       (streams/write-utf8-bytes
        out (str "mtllib " mtl "\nusemtl " (get opts :mtl "default") "\n\n")))
     (doseq [[x y z] (vals vertices)]
       (streams/write-utf8-bytes out (fmt-vertex x y z)))
     (doseq [[x y z] (vals vnormals)]
       (streams/write-utf8-bytes out (fmt-vnormal x y z)))
     (doseq [[u v] (vals uvs)]
       (streams/write-utf8-bytes out (fmt-uv u v)))
     (streams/write-utf8-bytes out "g\n")
     (if vnormals
       (if uvs
         (doseq [^thi.ng.geom.meshface.IndexedMeshFace f faces]
           (streams/write-utf8-bytes
            out (obj-fmt-face-v-uv-vn
                 (mapv inc (.-vertices f))
                 (mapv inc (get (.-attribs f) :uv))
                 (mapv inc (get (.-attribs f) :vnormals)))))
         (doseq [^thi.ng.geom.meshface.IndexedMeshFace f faces]
           (streams/write-utf8-bytes
            out (obj-fmt-face-v-vn
                 (mapv inc (.-vertices f))
                 (mapv inc (get (.-attribs f) :vnormals))))))
       (if uvs
         (doseq [^thi.ng.geom.meshface.IndexedMeshFace f faces]
           (streams/write-utf8-bytes
            out (obj-fmt-face-v-uv
                 (mapv inc (.-vertices f))
                 (mapv inc (get (.-attribs f) :uv)))))
         (doseq [^thi.ng.geom.meshface.IndexedMeshFace f faces]
           (streams/write-utf8-bytes
            out (obj-fmt-face-v
                 (mapv inc (.-vertices f)))))))
     out)))

;; OFF

(defn write-off
  "Writes mesh as OFF format to output stream wrapper.
  Returns stream wrapper."
  [out mesh]
  (let [vertices   (g/vertices mesh)
        faces      (g/faces mesh)
        vindex     (zipmap vertices (range))
        fmt-float  (f/float *precision*)
        fmt-vertex [fmt-float " " fmt-float " " fmt-float "\n"]]
    (streams/write-utf8-bytes out "OFF\n")
    (streams/write-utf8-bytes out (str (count vertices) " " (count faces) " 0\n"))
    (doseq [[x y z] vertices]
      (streams/write-utf8-bytes
       out (f/format fmt-vertex (double x) (double y) (double z))))
    (doseq [f faces :let [fverts (g/vertices f mesh)]]
      (streams/write-utf8-bytes
       out
       (str (count fverts) " "
            (str/join " " (map vindex fverts))
            "\n")))
    out))
