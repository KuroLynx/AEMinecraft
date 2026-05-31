package fr.euclesia.mcarchipelago.client.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A node of the serialized logic-rule AST shipped in {@code slot_data["logic"]}.
 *
 * <p>Mirrors {@code minecraft/rules/ast.py}: the only kinds that ever reach
 * serialization are {@code const}, {@code has}, {@code region}, {@code loc},
 * {@code and} and {@code or}. The JSON is parsed once into this tree (via
 * {@link #parse(JsonElement)}); evaluation then walks the tree against a
 * {@link LogicEvaluation} the same way {@code ExportEvaluator.eval} does in
 * {@code tools/logic_selfcheck.py}.
 */
public sealed interface RuleNode {
    boolean eval(LogicEvaluation evaluation);

    static RuleNode parse(JsonElement element) {
        JsonObject node = element.getAsJsonObject();
        String kind = node.get("k").getAsString();
        return switch (kind) {
            case "const" -> new Const(node.get("v").getAsBoolean());
            case "has" -> new Has(node.get("i").getAsString(), node.get("n").getAsInt());
            case "region" -> new Region(node.get("r").getAsString());
            case "loc" -> new Loc(node.get("l").getAsString());
            case "and" -> new And(parseChildren(node.getAsJsonArray("c")));
            case "or" -> new Or(parseChildren(node.getAsJsonArray("c")));
            default -> throw new IllegalArgumentException("unknown rule node kind: " + kind);
        };
    }

    private static List<RuleNode> parseChildren(JsonArray array) {
        List<RuleNode> children = new ArrayList<>(array.size());
        for (JsonElement child : array) {
            children.add(parse(child));
        }
        return children;
    }

    record Const(boolean value) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            return value;
        }
    }

    record Has(String item, int count) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            return evaluation.has(item, count);
        }
    }

    record Region(String region) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            return evaluation.canReachRegion(region);
        }
    }

    record Loc(String location) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            return evaluation.canReachLocation(location);
        }
    }

    record And(List<RuleNode> children) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            for (RuleNode child : children) {
                if (!child.eval(evaluation)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Or(List<RuleNode> children) implements RuleNode {
        @Override
        public boolean eval(LogicEvaluation evaluation) {
            for (RuleNode child : children) {
                if (child.eval(evaluation)) {
                    return true;
                }
            }
            return false;
        }
    }
}
