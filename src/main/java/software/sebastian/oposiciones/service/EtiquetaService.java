package software.sebastian.oposiciones.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.sebastian.oposiciones.model.ArbolEtiqueta;
import software.sebastian.oposiciones.model.Etiqueta;
import software.sebastian.oposiciones.repository.ArbolEtiquetaRepository;
import software.sebastian.oposiciones.repository.EtiquetaRepository;


@Service
public class EtiquetaService {
    private final EtiquetaRepository etiquetaRepo;
    private final ArbolEtiquetaRepository arbolRepo;
    private final EtiquetaEmbeddingService etiquetaEmbeddingService;

    public EtiquetaService(EtiquetaRepository er, ArbolEtiquetaRepository ar,
            EtiquetaEmbeddingService etiquetaEmbeddingService) {
        this.etiquetaRepo = er;
        this.arbolRepo = ar;
        this.etiquetaEmbeddingService = etiquetaEmbeddingService;
    }

public List<Etiqueta> getEtiquetasEnOrdenArbol() {
    List<TreeNode> forest = getTree();
    List<Etiqueta> ordered = new ArrayList<>();
    for (TreeNode node : forest) {
        preOrder(node, ordered);
    }
    return ordered;
}


public Map<Integer, List<Integer>> obtenerRelacionesPadreHijo() {
    List<ArbolEtiqueta> relaciones = arbolRepo.findAll();
    Map<Integer, List<Integer>> mapa = new HashMap<>();

    for (ArbolEtiqueta ae : relaciones) {
        Integer padre = ae.getAncestro().getEtiquetaId();
        Integer hijo = ae.getDescendiente().getEtiquetaId();

        if (!padre.equals(hijo)) {
            mapa.computeIfAbsent(padre, k -> new ArrayList<>()).add(hijo);
        }
    }

    return mapa;
}


private void preOrder(TreeNode node, List<Etiqueta> result) {
    result.add(node.getEtiqueta()); // Suponiendo que TreeNode tiene getEtiqueta()
    for (TreeNode child : node.getChildren()) {
        preOrder(child, result);
    }
}

    public List<Etiqueta> findAll() {
        return etiquetaRepo.findAll();
    }

    @Transactional
    public Etiqueta create(String nombre, String formato) {
        Etiqueta e = new Etiqueta();
        e.setNombre(nombre);
        if (formato == null || formato.trim().isEmpty()) {
            formato = "Convocatorias de oposiciones y anuncios relevantes a %s";
        }
        e.setFormato(formato);
        Etiqueta eSaved = etiquetaRepo.save(e);
        arbolRepo.save(new ArbolEtiqueta(eSaved, eSaved, 0));

        // Generar embedding asincrónicamente si quieres, o síncrono aquí
        etiquetaEmbeddingService.generarYGuardarEmbedding(eSaved.getEtiquetaId());

        return eSaved;
    }

    @Transactional
    public Etiqueta createWithParent(String nombre, String descripcion, Integer parentId) {
        Etiqueta e = create(nombre, descripcion);
        if (parentId != null) {
            addRelation(parentId, e.getEtiquetaId());
        }
        return e;
    }

    @Transactional
    public void moveSubtree(Integer nodeId, Integer parentId) {
        arbolRepo.bulkReparent(nodeId, parentId);
    }

    public Etiqueta update(Integer id, String nombre, String formato) {
        Etiqueta e = etiquetaRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe etiqueta: " + id));
        e.setNombre(nombre);
        e.setFormato(formato);
        Etiqueta saved = etiquetaRepo.save(e);

        // Actualizar embedding
        etiquetaEmbeddingService.generarYGuardarEmbedding(saved.getEtiquetaId());

        return saved;
    }

    /**
     * Elimina la etiqueta id y reparenta su sub‐árbol: - sus hijos directos pasan a colgar de su
     * padre (si existía). - si era raíz, sus hijos se convierten en raíces (sin ancestros).
     */
    @Transactional
    public void delete(Integer id) {
        Etiqueta toDelete = etiquetaRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe etiqueta " + id));

        // 1) Hijos directos y todo el sub‐árbol de 'id'
        List<Integer> directChildren = arbolRepo.findDirectChildrenIds(id);

        // 2) Padre directo (si existía)
        Optional<Integer> parentOpt =
                arbolRepo.findAncestorsOf(id).stream().filter(r -> r.getDistancia() == 1)
                        .map(r -> r.getAncestro().getEtiquetaId()).findFirst();

        // 3) Si tenía padre, muevo cada hijo directamente
        // al nuevo padre (o al root si parentOpt está vacío)
        Integer parentId = parentOpt.orElse(null);
        for (Integer childId : directChildren) {
            // mover el subtree cuyo root es childId para que cuelgue de parentId
            arbolRepo.bulkReparent(childId, parentId);
        }

        // 4) Borro todas las filas de la closure‐table de / hacia 'id'
        List<ArbolEtiqueta> ancestorsRels = arbolRepo.findAncestorsOf(id);
        List<ArbolEtiqueta> descendantsRels = arbolRepo.findRelationsByAncestor(id);
        arbolRepo.deleteAll(ancestorsRels);
        arbolRepo.deleteAll(descendantsRels);

        // 5) Finalmente, borro la propia entidad
        etiquetaRepo.delete(toDelete);
    }

    public static class TreeNode {
        public Etiqueta etiqueta;
        public Etiqueta getEtiqueta() {
            return etiqueta;
        }

        public void setEtiqueta(Etiqueta etiqueta) {
            this.etiqueta = etiqueta;
        }

        public List<TreeNode> children = new ArrayList<>();

        public List<TreeNode> getChildren() {
            return children;
        }

        public void setChildren(List<TreeNode> children) {
            this.children = children;
        }

        public TreeNode(Etiqueta e) {
            this.etiqueta = e;
        }
    }

    /**
     * Lee todas las etiquetas y relaciones distancia=1, y monta un forest (listado de raíces
     * TreeNode).
     */
    public List<TreeNode> getTree() {
        List<Etiqueta> all = etiquetaRepo.findAll();
        List<ArbolEtiqueta> rels = arbolRepo.findAllDirectRelations();

        // Mapa etiquetaId → nodo
        Map<Integer, TreeNode> nodes =
                all.stream().collect(Collectors.toMap(Etiqueta::getEtiquetaId, TreeNode::new));

        // Conjunto de hijos (para luego identificar raíces)
        Set<Integer> childrenIds = new HashSet<>();

        // Construyo parent→children
        for (ArbolEtiqueta r : rels) {
            Integer p = r.getAncestro().getEtiquetaId();
            Integer c = r.getDescendiente().getEtiquetaId();
            TreeNode parent = nodes.get(p);
            TreeNode child = nodes.get(c);
            parent.children.add(child);
            childrenIds.add(c);
        }

        // Ordeno los hijos de cada nodo alfabéticamente por nombre
        for (TreeNode node : nodes.values()) {
            node.children.sort(Comparator.comparing(n -> n.etiqueta.getNombre()));
        }

        // Raíces = todas menos los que son hijos
        return nodes.values().stream()
                .filter(n -> !childrenIds.contains(n.etiqueta.getEtiquetaId()))
                .sorted(Comparator.comparing(o -> o.etiqueta.getNombre()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void addRelation(Integer parentId, Integer childId) {
        if (parentId.equals(childId))
            throw new IllegalArgumentException("No self‐loops");
        Etiqueta p = etiquetaRepo.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Padre no existe"));
        Etiqueta c = etiquetaRepo.findById(childId)
                .orElseThrow(() -> new IllegalArgumentException("Hijo no existe"));

        // 1) inserta p→c (dist=1)
        arbolRepo.save(new ArbolEtiqueta(p, c, 1));
        // 2) para cada ancestro A de p, inserta A→c con distancia+1
        arbolRepo.findAncestorsOf(parentId).forEach(rel -> arbolRepo
                .save(new ArbolEtiqueta(rel.getAncestro(), c, rel.getDistancia() + 1)));
    }

}
