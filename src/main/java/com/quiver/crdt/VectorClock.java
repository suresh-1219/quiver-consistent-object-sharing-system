package com.quiver.crdt;

import java.util.HashMap;
import java.util.Map;

public class VectorClock {

    private Map<String, Integer> clock = new HashMap<>();

   
    public void increment(String nodeId) {
        clock.merge(nodeId, 1, Integer::sum);
    }

   
    public String compare(VectorClock other) {
        boolean thisLessOrEqual = true;
        boolean otherLessOrEqual = true;

    
        java.util.Set<String> allNodes = new java.util.HashSet<>();
        allNodes.addAll(this.clock.keySet());
        allNodes.addAll(other.clock.keySet());

        for (String node : allNodes) {
            int thisVal = this.clock.getOrDefault(node, 0);
            int otherVal = other.clock.getOrDefault(node, 0);

            if (thisVal > otherVal) {
                thisLessOrEqual = false;
            }
            if (thisVal < otherVal) {
                otherLessOrEqual = false;
            }
        }

        if (thisLessOrEqual && otherLessOrEqual) {
            return "EQUAL";
        } else if (thisLessOrEqual) {
            return "BEFORE";   
        } else if (otherLessOrEqual) {
            return "AFTER";   
        } else {
            return "CONCURRENT"; 
        }
    }

    public Map<String, Integer> getClock() {
        return clock;
    }

   
    @Override
    public String toString() {
        return clock.toString();
    }
}
