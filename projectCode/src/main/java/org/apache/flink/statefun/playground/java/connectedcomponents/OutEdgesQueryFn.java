package org.apache.flink.statefun.playground.java.connectedcomponents;

import org.apache.flink.statefun.playground.java.connectedcomponents.types.*;
import org.apache.flink.statefun.sdk.java.*;
import org.apache.flink.statefun.sdk.java.message.EgressMessageBuilder;
import org.apache.flink.statefun.sdk.java.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This function processes the query for counting the number of incoming edges of a vertex
 * In practice, there will be multiple logical instances of the InEdgesQueryFn, and the number of logical
 * instances will be equal to the number of vertices in the graph. Each logical instance will be identified by the
 * address (InEdgesQueryFn.TYPE_NAME, vertex_id). In this case, each logical instance only needs to store the incoming
 * edges for a specific vertex.
 * To send a query message to this function, please build a message with the IN_EDGES_QUERY_TYPE in {@link Types} and
 * send to the address described above, where the vertex_id is the vertex we want to query
 */
public class OutEdgesQueryFn implements StatefulFunction {

    private static final ValueSpec<List<CustomTuple2<Integer, Long>>> OUT_NEIGHBORS =
            ValueSpec.named("outNeighbors").withCustomType(Types.OUT_NEIGHBORS_TYPE);

    static final TypeName TYPE_NAME = TypeName.typeNameOf("connected-components.fns", "outEdges");
    static final StatefulFunctionSpec SPEC =
            StatefulFunctionSpec.builder(TYPE_NAME)
                    .withSupplier(OutEdgesQueryFn::new)
                    .withValueSpecs(OUT_NEIGHBORS)
                    .build();

    static final TypeName EGRESS_TYPE = TypeName.typeNameOf("io.statefun.playground", "egress");

    @Override
    public CompletableFuture<Void> apply(Context context, Message message) throws Throwable {
        if (message.is(Types.Add_OUT_EDGE_TYPE)) {
            Vertex vertex = message.as(Types.Add_OUT_EDGE_TYPE);
            List<CustomTuple2<Integer, Long>> currentOutNeighbors = getCurrentOutNeighbors(context);
            updateOutNeighbors(context, vertex, currentOutNeighbors);
            logOutNeighbors(vertex.getSrc(), context);
        } else if (message.is(Types.OUT_EDGES_QUERY_TYPE)) {
            OutEdgesQuery query = message.as(Types.OUT_EDGES_QUERY_TYPE);
            // the query we are implementing now is simple; it is only asking for all the incoming edges, so we can
            // just return the entire IN_NEIGHBORS list
            outputResult(context, query.getVertexId());
        }
        return context.done();
    }

    /**
     * This method returns the current incoming neighbors of a vertex
     * @param context
     * @return IN_NEIGHBORS
     */
    private List<CustomTuple2<Integer, Long>> getCurrentOutNeighbors(Context context) {
        return context.storage().get(OUT_NEIGHBORS).orElse(new ArrayList<CustomTuple2<Integer, Long>>());
    }

    /**
     * This method update the IN_NEIGHBORS list by adding a new outgoing neighbor to the list
     * while ensuring that all the neighbors in the list are sorted by timestamp value
     * @param context
     * @param vertex
     * @param currentOutNeighbors
     */
    private void updateOutNeighbors(Context context, Vertex vertex, List<CustomTuple2<Integer, Long>> currentOutNeighbors) {
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

    /**
     * This method outputs query result to egress.
     * @param context
     * @param vertexId
     */
    private void outputResult(Context context, int vertexId) {
        List<CustomTuple2<Integer, Long>> currentOutNeighbors =
                context.storage().get(OUT_NEIGHBORS).orElse(Collections.emptyList());

        context.send(
                EgressMessageBuilder.forEgress(EGRESS_TYPE)
                        .withCustomType(Types.EGRESS_RECORD_JSON_TYPE,
                                new EgressRecord("outgoing-edges",
                                        String.format("The outgoing edges of vertex %s are %s", vertexId, currentOutNeighbors)))
                        .build()
        );
    }

    /**
     * This methods prints out the current incoming edges/neighbors of a vertex
     * @param vertex
     * @param context
     */
    private void logOutNeighbors(int vertex, Context context) {
        List<CustomTuple2<Integer, Long>> currentOutNeighbors = context.storage().get(OUT_NEIGHBORS).orElse(Collections.emptyList());

        System.out.printf("vertex %d currently has these outgoing neighbors: %s\n", vertex, currentOutNeighbors);
    }
}