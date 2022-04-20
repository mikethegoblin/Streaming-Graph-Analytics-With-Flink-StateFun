package org.apache.flink.statefun.playground.java.graphanalytics;

import org.apache.flink.statefun.playground.java.graphanalytics.types.*;
import org.apache.flink.statefun.sdk.java.*;
import org.apache.flink.statefun.sdk.java.message.EgressMessageBuilder;
import org.apache.flink.statefun.sdk.java.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TwoHopQueryFn implements StatefulFunction {


    private static final ValueSpec<List<CustomTuple2<Integer, Long>>> OUT_NEIGHBORS =
            ValueSpec.named("outNeighbors").withCustomType(Types.OUT_NEIGHBORS_TYPE);

    private static final ValueSpec<List<CustomTuple2<Integer, Long>>> IN_NEIGHBORS =
            ValueSpec.named("inNeighbors").withCustomType(Types.IN_NEIGHBORS_TYPE);

    private static final ValueSpec<List<CustomTuple2<Integer, Long>>> TWOHOP_NEIGHBORS =
            ValueSpec.named("TwoHopNeighbors").withCustomType(Types.TwoHop_NEIGHBORS_TYPE);


    static final TypeName TYPE_NAME = TypeName.typeNameOf("graph-analytics.fns", "twoHopEdges");
    static final StatefulFunctionSpec SPEC =
            StatefulFunctionSpec.builder(TYPE_NAME)
                    .withSupplier(TwoHopQueryFn::new)
                    .withValueSpecs(IN_NEIGHBORS,OUT_NEIGHBORS,TWOHOP_NEIGHBORS)
                    .build();

    static final TypeName EGRESS_TYPE = TypeName.typeNameOf("io.statefun.playground", "egress");

    @Override
    public CompletableFuture<Void> apply(Context context, Message message) throws Throwable {
        if (message.is(Types.VERTEX_INIT_TYPE)) {
            Vertex vertex = message.as(Types.VERTEX_INIT_TYPE);
//            List<CustomTuple2<Integer,Long>> currentTwoHopNeighbors = getCurrentTwoHopNeighbors(context);
            updateTwoHopNeighbors(context,vertex);
        
        
        } else if (message.is(Types.Two_Hop_QUERY_TYPE)){
            TwoHopQuery hopQuery = message.as(Types.Two_Hop_QUERY_TYPE);
            outputResult(context,hopQuery.getVertexId());
        }

        return context.done();
    }


    public List<CustomTuple2<Integer, Long>> getCurrentTwoHopNeighbors(Context context) {
        return context.storage().get(TWOHOP_NEIGHBORS).orElse(new ArrayList<CustomTuple2<Integer, Long>>());
    }

    public List<CustomTuple2<Integer, Long>> getCurrentInNeighbors(Context context) {
        return context.storage().get(IN_NEIGHBORS).orElse(new ArrayList<CustomTuple2<Integer, Long>>());
    }


    public List<CustomTuple2<Integer, Long>> getCurrentOutNeighbors(Context context) {
        return context.storage().get(OUT_NEIGHBORS).orElse(new ArrayList<CustomTuple2<Integer, Long>>());
    }


    public void updateTwoHopNeighbors(Context context, Vertex vertex) {
        List<CustomTuple2<Integer, Long>> currentInNeighbors = getCurrentInNeighbors(context);
        updateInNeighbors(context, vertex, currentInNeighbors);
        List<CustomTuple2<Integer,Long>> currentOutNeighbors = getCurrentOutNeighbors(context);


//        String s = String.format("current incoming neighbots: %s", currentInNeighbors);
//        System.out.println(s);
        for (CustomTuple2<Integer, Long> each : currentInNeighbors){
            Integer src = each.getField(0);
            Long tsp = each.getField(1);
            if (!src.equals(vertex.getSrc())){

                List<CustomTuple2<Integer,Long>> currentTwoHopNeighbors = getCurrentTwoHopNeighbors(context);
                Vertex v = new Vertex(src,src,tsp);


                updateOutNeighbors(context,v,currentOutNeighbors);
                currentTwoHopNeighbors.addAll(currentOutNeighbors);
                context.storage().set(TWOHOP_NEIGHBORS,currentTwoHopNeighbors);
                String s = String.format("current twoHop neighbors: %s", currentTwoHopNeighbors);
                System.out.println(s);
            }


        }

    }
    public void updateOutNeighbors(Context context, Vertex vertex, List<CustomTuple2<Integer, Long>> currentOutNeighbors) {
        CustomTuple2<Integer, Long> newOutNeighbor = CustomTuple2.createTuple2(vertex.getDst(), vertex.getTimestamp());
        // perform binary search to add incoming neighbor to the correct index, so that the IN_NEIGHBORS list remains
        // sorted by timestamp
        int left = 0, right = currentOutNeighbors.size() - 1;
        int insertIdx = 0;
        while (left <= right) {
            int mid = left + (right-left)/2;
            Long t1 = currentOutNeighbors.get(mid).getField(1);
            Long t2 = newOutNeighbor.getField(1);
            int comparison = t1.compareTo(t2);
            if (comparison == 0) {
                insertIdx = mid;
                break;
            } else if (comparison < 0) {
                left = mid + 1;
                insertIdx = left;
            } else {
                right = mid - 1;
            }
        }
        currentOutNeighbors.add(insertIdx, newOutNeighbor);
        context.storage().set(OUT_NEIGHBORS, currentOutNeighbors);
    }

    public void updateInNeighbors(Context context, Vertex vertex, List<CustomTuple2<Integer, Long>> currentInNeighbors) {
        CustomTuple2<Integer, Long> newInNeighbor = CustomTuple2.createTuple2(vertex.getSrc(), vertex.getTimestamp());
        // perform binary search to add incoming neighbor to the correct index, so that the IN_NEIGHBORS list remains
        // sorted by timestamp
        int left = 0, right = currentInNeighbors.size() - 1;
        int insertIdx = 0;
        while (left <= right) {
            int mid = left + (right-left)/2;
            Long t1 = currentInNeighbors.get(mid).getField(1);
            Long t2 = newInNeighbor.getField(1);
            int comparison = t1.compareTo(t2);
            if (comparison == 0) {
                insertIdx = mid;
                break;
            } else if (comparison < 0) {
                left = mid + 1;
                insertIdx = left;
            } else {
                right = mid - 1;
            }
        }
        currentInNeighbors.add(insertIdx, newInNeighbor);
        context.storage().set(IN_NEIGHBORS, currentInNeighbors);
    }

    private void outputResult(Context context, int vertexId) {
        List<CustomTuple2<Integer, Long>> TwoHopNeighbors =
                context.storage().get(TWOHOP_NEIGHBORS).orElse(Collections.emptyList());

        context.send(
                EgressMessageBuilder.forEgress(EGRESS_TYPE)
                        .withCustomType(Types.EGRESS_RECORD_JSON_TYPE,
                                new EgressRecord("TwoHop-Recommendation",
                                        String.format("Recommended node connection for vertex %s are %s", vertexId, TwoHopNeighbors)))
                        .build()
        );
    }
}
