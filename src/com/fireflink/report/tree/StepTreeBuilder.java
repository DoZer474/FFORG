package com.fireflink.report.tree;

import com.fireflink.report.model.StepResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The step-results API returns a flat list where each record's parentId
 * points to another record's id (or to the script itself, for top-level
 * steps). This rebuilds that into an actual tree so the HTML generator can
 * render nested, collapsible groups instead of a flat dump.
 */
public class StepTreeBuilder {

    /**
     * @param steps      flat list of all steps for one script (any order)
     * @param scriptId   the script's own id - top-level steps have
     *                   parentId/originalParentId equal to this
     * @return root-level steps, each with .children populated recursively,
     *         sorted by displayOrder at every level
     */
    public static List<StepResult> buildTree(List<StepResult> steps, String scriptId) {
        Map<String, StepResult> byId = new HashMap<>();
        for (StepResult s : steps) {
            byId.put(s.id, s);
        }

        List<StepResult> roots = new ArrayList<>();

        for (StepResult s : steps) {
            String parentId = s.parentId;
            StepResult parent = (parentId != null) ? byId.get(parentId) : null;

            boolean isTopLevel = parent == null || parentId.equals(scriptId)
                    || (s.originalParentId != null && s.originalParentId.equals(scriptId) && parent == null);

            if (isTopLevel) {
                roots.add(s);
            } else {
                parent.children.add(s);
            }
        }

        sortRecursively(roots);
        return roots;
    }

    private static void sortRecursively(List<StepResult> nodes) {
        nodes.sort(Comparator.comparingInt(n -> n.displayOrder));
        for (StepResult n : nodes) {
            if (!n.children.isEmpty()) {
                sortRecursively(n.children);
            }
        }
    }
}
