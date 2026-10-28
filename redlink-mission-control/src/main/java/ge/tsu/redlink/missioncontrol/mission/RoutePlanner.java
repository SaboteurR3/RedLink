package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Finds the cheapest drive to a cell: A* over (cell, heading), where moving one cell forward costs
 * its battery (sand more than ground) and a quarter turn costs {@link RoverSpecs#ROTATE_COST}. Rocks,
 * the map edge and slopes steeper than {@link RoverSpecs#MAX_SLOPE_DEGREES} are never entered. The
 * result is the command list the rover understands (ROTATE / MOVE ≤ 20 m); battery and storage are
 * then checked by {@link MissionPlanner}, like any plan.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RoutePlanner {

    /** Ties between equally cheap routes go to the one with fewer turns (time matters too). */
    private static final double TURN_PENALTY = 0.01;
    private static final int MAX_MOVE = 20;
    private static final Heading[] HEADINGS = Heading.values(); // NORTH, EAST, SOUTH, WEST: clockwise

    /**
     * @return the commands, or empty if no drivable route exists (or the target cannot be entered)
     */
    public static Optional<List<RoverCommand>> route(Terrain terrain, int fromX, int fromY, Heading heading, int toX, int toY) {
        if (!terrain.contains(toX, toY) || terrain.isBlocked(toX, toY)) {
            return Optional.empty();
        }
        if (fromX == toX && fromY == toY) {
            return Optional.of(List.of());
        }
        Node start = new Node(fromX, fromY, heading);
        Map<Node, Double> best = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>();
        best.put(start, 0.0);
        open.add(new Entry(start, 0, estimate(start, toX, toY)));

        while (!open.isEmpty()) {
            Entry current = open.poll();
            Node node = current.node();
            if (current.cost() > best.getOrDefault(node, Double.MAX_VALUE)) {
                continue; // a cheaper way here was found meanwhile
            }
            if (node.x() == toX && node.y() == toY) {
                return Optional.of(commands(path(cameFrom, node)));
            }
            for (Step step : steps(terrain, node)) {
                double cost = current.cost() + step.cost();
                if (cost < best.getOrDefault(step.to(), Double.MAX_VALUE)) {
                    best.put(step.to(), cost);
                    cameFrom.put(step.to(), node);
                    open.add(new Entry(step.to(), cost, cost + estimate(step.to(), toX, toY)));
                }
            }
        }
        return Optional.empty();
    }

    private static List<Step> steps(Terrain terrain, Node node) {
        List<Step> steps = new ArrayList<>(3);
        int i = node.heading().ordinal();
        steps.add(new Step(new Node(node.x(), node.y(), HEADINGS[(i + 1) % 4]), RoverSpecs.ROTATE_COST + TURN_PENALTY));
        steps.add(new Step(new Node(node.x(), node.y(), HEADINGS[(i + 3) % 4]), RoverSpecs.ROTATE_COST + TURN_PENALTY));
        int toX = node.x() + dx(node.heading());
        int toY = node.y() + dy(node.heading());
        if (terrain.contains(toX, toY) && !terrain.isBlocked(toX, toY)
                && terrain.slopeDegrees(node.x(), node.y(), toX, toY) <= RoverSpecs.MAX_SLOPE_DEGREES) {
            double cost = terrain.surfaceAt(toX, toY) == SurfaceType.SAND ? RoverSpecs.MOVE_COST_SAND : RoverSpecs.MOVE_COST_GROUND;
            steps.add(new Step(new Node(toX, toY, node.heading()), cost));
        }
        return steps;
    }

    /** Never more than the true cost: every remaining cell costs at least the ground price. */
    private static double estimate(Node node, int toX, int toY) {
        return (Math.abs(toX - node.x()) + Math.abs(toY - node.y())) * RoverSpecs.MOVE_COST_GROUND;
    }

    private static List<Node> path(Map<Node, Node> cameFrom, Node end) {
        List<Node> path = new ArrayList<>();
        for (Node node = end; node != null; node = cameFrom.get(node)) {
            path.add(node);
        }
        Collections.reverse(path);
        return path;
    }

    /** Turns in place become one ROTATE to the final heading; straight runs become MOVEs of at most 20 m. */
    private static List<RoverCommand> commands(List<Node> path) {
        List<RoverCommand> commands = new ArrayList<>();
        int run = 0;
        for (int i = 1; i < path.size(); i++) {
            Node previous = path.get(i - 1);
            Node next = path.get(i);
            if (next.heading() != previous.heading()) {
                run = flush(commands, run);
                boolean moreTurns = i + 1 < path.size() && path.get(i + 1).x() == next.x() && path.get(i + 1).y() == next.y();
                if (!moreTurns) {
                    commands.add(new RoverCommand.Rotate(next.heading()));
                }
            } else {
                run++;
                if (run == MAX_MOVE) {
                    run = flush(commands, run);
                }
            }
        }
        flush(commands, run);
        return commands;
    }

    private static int flush(List<RoverCommand> commands, int run) {
        if (run > 0) {
            commands.add(new RoverCommand.Move(run));
        }
        return 0;
    }

    private static int dx(Heading heading) {
        return switch (heading) {
            case EAST -> 1;
            case WEST -> -1;
            case NORTH, SOUTH -> 0;
        };
    }

    private static int dy(Heading heading) {
        return switch (heading) {
            case NORTH -> -1;
            case SOUTH -> 1;
            case EAST, WEST -> 0;
        };
    }

    private record Node(int x, int y, Heading heading) {
    }

    private record Step(Node to, double cost) {
    }

    private record Entry(Node node, double cost, double priority) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            return Double.compare(priority, other.priority);
        }
    }
}
