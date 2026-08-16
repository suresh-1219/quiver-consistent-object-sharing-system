package com.quiver.crdt;

public class LWWRegister<T> {

    private T value;
    private VectorClock timestamp;
    private String lastWriterNodeId;   // tie-break

    public LWWRegister() {
        this.timestamp = new VectorClock();
    }

 
    public void update(T newValue, String nodeId) {
        VectorClock newTimestamp = new VectorClock();
   
        newTimestamp.getClock().putAll(this.timestamp.getClock());
        newTimestamp.increment(nodeId);

        applyUpdate(newValue, newTimestamp, nodeId);
    }

   
    public void merge(T incomingValue, VectorClock incomingTimestamp, String incomingNodeId) {
        applyUpdate(incomingValue, incomingTimestamp, incomingNodeId);
    }

    private void applyUpdate(T newValue, VectorClock newTimestamp, String nodeId) {
        String comparison = newTimestamp.compare(this.timestamp);

        if (comparison.equals("AFTER")) {
           
            this.value = newValue;
            this.timestamp = newTimestamp;
            this.lastWriterNodeId = nodeId;

        } else if (comparison.equals("CONCURRENT")) {
        
            if (nodeId.compareTo(this.lastWriterNodeId) > 0) {
                this.value = newValue;
                this.timestamp = newTimestamp;
                this.lastWriterNodeId = nodeId;
            }
          
        }
   
    }

    public T getValue() {
        return value;
    }

    public VectorClock getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "value=" + value + ", timestamp=" + timestamp + ", lastWriter=" + lastWriterNodeId;
    }

}
