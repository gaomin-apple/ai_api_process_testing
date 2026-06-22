package com.labway.aft.engine;

import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.FlowDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlowValidator {
    public List<String> validate(FlowDefinition flow, Map<String, EndpointDefinition> endpoints) {
        return validate(flow, endpoints, true);
    }

    public List<String> validateForSave(FlowDefinition flow, Map<String, EndpointDefinition> endpoints) {
        return validate(flow, endpoints, false);
    }

    private List<String> validate(
            FlowDefinition flow,
            Map<String, EndpointDefinition> endpoints,
            boolean requireExecutableGateway
    ) {
        List<String> errors = new ArrayList<>();
        if (flow.nodes().isEmpty()) {
            return List.of("Flow must contain at least one node");
        }

        Map<String, FlowDefinition.FlowNode> nodes = new HashMap<>();
        for (FlowDefinition.FlowNode node : flow.nodes()) {
            if (nodes.put(node.id(), node) != null) {
                errors.add("Duplicate node id: " + node.id());
            }
            if (node.gatewayNode() && requireExecutableGateway) {
                if (node.gateway() == null) {
                    errors.add("Gateway " + node.name() + " is missing configuration");
                } else if ("VARIABLE".equals(node.gateway().sourceType())
                        && (node.gateway().source() == null || node.gateway().source().isBlank())) {
                    errors.add("Gateway " + node.name() + " must select a variable");
                }
                continue;
            }
            if (node.gatewayNode() || node.parallelNode()) {
                continue;
            }
            EndpointDefinition endpoint = endpoints.get(node.endpointId());
            if (endpoint == null || !endpoint.active()) {
                errors.add("Node " + node.name() + " references an unavailable endpoint");
                continue;
            }
            for (EndpointDefinition.ParameterDefinition parameter : endpoint.parameters()) {
                if (!parameter.required()) {
                    continue;
                }
                String value = switch (parameter.location()) {
                    case "path" -> node.request().path().get(parameter.name());
                    case "query" -> node.request().query().get(parameter.name());
                    case "header" -> node.request().headers().get(parameter.name());
                    default -> "supported-by-runtime";
                };
                if (value == null || value.isBlank()) {
                    errors.add("Node " + node.name() + " is missing required "
                            + parameter.location() + " parameter: " + parameter.name());
                }
            }
        }

        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        for (FlowDefinition.FlowEdge edge : flow.edges()) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                errors.add("Edge " + edge.id() + " references an unknown node");
                continue;
            }
            outgoing.merge(edge.source(), 1, Integer::sum);
            incoming.merge(edge.target(), 1, Integer::sum);
        }
        long starts = flow.nodes().stream().filter(node -> incoming.getOrDefault(node.id(), 0) == 0).count();
        if (starts != 1) {
            errors.add("Flow must have exactly one start node");
        }
        if (requireExecutableGateway) {
            for (Map.Entry<String, Integer> entry : outgoing.entrySet()) {
                if (entry.getValue() > 1) {
                    FlowDefinition.FlowNode sourceNode = nodes.get(entry.getKey());
                    if (sourceNode != null && sourceNode.parallelNode()) {
                        continue;
                    }
                    List<FlowDefinition.FlowEdge> outEdges = flow.edges().stream()
                            .filter(e -> e.source().equals(entry.getKey()))
                            .toList();
                    long withCondition = outEdges.stream().filter(e -> e.condition() != null).count();
                    if (withCondition == 0) {
                        continue;
                    }
                    if (withCondition != outEdges.size() - 1) {
                        errors.add("Node " + entry.getKey() + " has " + outEdges.size()
                                + " outgoing edges but " + withCondition + " have conditions; "
                                + "exactly " + (outEdges.size() - 1) + " must have conditions (1 default fallback)");
                    }
                    outEdges.stream()
                            .filter(edge -> edge.condition() != null)
                            .filter(edge -> !"EXISTS".equalsIgnoreCase(edge.condition().operator()))
                            .filter(edge -> edge.condition().expected() == null
                                    || edge.condition().expected().isBlank())
                            .forEach(edge -> errors.add(
                                    "Branch from node " + entry.getKey()
                                            + " to " + edge.target() + " is missing an expected value"
                            ));
                }
            }
            for (FlowDefinition.FlowNode node : flow.nodes()) {
                if (node.gatewayNode() && outgoing.getOrDefault(node.id(), 0) < 2) {
                    errors.add("Gateway " + node.name() + " must connect at least two outgoing branches");
                }
                if (node.parallelNode() && outgoing.getOrDefault(node.id(), 0) < 2) {
                    errors.add("Parallel fork " + node.name() + " must connect at least two outgoing branches");
                }
            }
        }
        if (containsCycle(flow)) {
            errors.add("Flow must not contain a cycle");
        }
        return errors;
    }

    public List<FlowDefinition.FlowNode> startNodes(FlowDefinition flow) {
        Set<String> targets = new HashSet<>();
        flow.edges().forEach(edge -> targets.add(edge.target()));
        Map<String, FlowDefinition.FlowNode> byId = new HashMap<>();
        flow.nodes().forEach(node -> byId.put(node.id(), node));
        return flow.nodes().stream()
                .filter(node -> !targets.contains(node.id()))
                .toList();
    }

    public List<FlowDefinition.FlowNode> nextNodes(
            FlowDefinition.FlowNode current,
            FlowDefinition flow,
            Map<String, String> variables
    ) {
        Map<String, FlowDefinition.FlowNode> byId = new HashMap<>();
        flow.nodes().forEach(node -> byId.put(node.id(), node));
        List<FlowDefinition.FlowEdge> outEdges = flow.edges().stream()
                .filter(e -> e.source().equals(current.id()))
                .toList();
        if (outEdges.isEmpty()) {
            return List.of();
        }
        String gatewayValue = current.gatewayNode() ? resolveGatewayValue(current.gateway(), variables) : null;
        for (FlowDefinition.FlowEdge edge : outEdges) {
            if (edge.condition() != null && evaluateCondition(edge.condition(), variables, gatewayValue)) {
                FlowDefinition.FlowNode target = byId.get(edge.target());
                return target != null ? List.of(target) : List.of();
            }
        }
        FlowDefinition.FlowEdge fallback = outEdges.stream()
                .filter(e -> e.condition() == null)
                .findFirst()
                .orElse(null);
        if (fallback != null) {
            FlowDefinition.FlowNode target = byId.get(fallback.target());
            return target != null ? List.of(target) : List.of();
        }
        return List.of();
    }

    private String resolveGatewayValue(
            FlowDefinition.GatewayConfig gateway,
            Map<String, String> variables
    ) {
        if (gateway == null) {
            return null;
        }
        if ("FIXED".equals(gateway.sourceType())) {
            return gateway.fixedValue();
        }
        String source = gateway.source();
        if (source == null) {
            return null;
        }
        String actual = variables.get(source);
        return actual != null ? actual : variables.get("run." + source);
    }

    private boolean evaluateCondition(
            FlowDefinition.Condition condition,
            Map<String, String> variables,
            String gatewayValue
    ) {
        String actual = gatewayValue;
        if (actual == null) {
            actual = variables.get(condition.source());
            if (actual == null) {
                actual = variables.get("run." + condition.source());
            }
        }
        if (actual == null) {
            return false;
        }
        String op = condition.operator() == null ? "EQUALS" : condition.operator().toUpperCase();
        String expected = condition.expected() == null ? "" : condition.expected();
        return switch (op) {
            case "EQUALS" -> actual.equals(expected);
            case "NOT_EQUALS" -> !actual.equals(expected);
            case "CONTAINS" -> actual.contains(expected);
            case "EXISTS" -> true;
            case "LESS_THAN" -> {
                try {
                    yield Double.parseDouble(actual) < Double.parseDouble(expected);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private boolean containsCycle(FlowDefinition flow) {
        Map<String, List<String>> next = new HashMap<>();
        flow.edges().forEach(edge -> next.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target()));
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (FlowDefinition.FlowNode node : flow.nodes()) {
            if (hasCycle(node.id(), next, visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(String nodeId, Map<String, List<String>> next, Set<String> visited, Set<String> inStack) {
        if (inStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }
        visited.add(nodeId);
        inStack.add(nodeId);
        for (String target : next.getOrDefault(nodeId, List.of())) {
            if (hasCycle(target, next, visited, inStack)) {
                return true;
            }
        }
        inStack.remove(nodeId);
        return false;
    }
}
