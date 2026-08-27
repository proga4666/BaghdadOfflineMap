package com.offlinemap.baghdad.engine

import com.graphhopper.routing.util.FlagEncoder
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PMap
import com.offlinemap.baghdad.data.cache.LearnedEdgeStore

class GoogleBiasedWeighting(
    encoder: FlagEncoder,
    hintsMap: PMap,
    private val edgeStore: LearnedEdgeStore
) : FastestWeighting(encoder, hintsMap) {

    constructor(encoder: FlagEncoder, edgeStore: LearnedEdgeStore) : this(encoder, PMap(), edgeStore)

    override fun calcWeight(edgeState: EdgeIteratorState, reverse: Boolean, prevOrNextEdgeId: Int): Double {
        val baseWeight = super.calcWeight(edgeState, reverse, prevOrNextEdgeId)
        val edgeId = edgeState.edge

        // If this road segment was previously chosen/validated by Google Directions,
        // give it a 75% discount in cost (making it 4x more attractive in offline Dijkstra/A* path search)
        return if (edgeStore.isLearnedEdge(edgeId)) {
            baseWeight * 0.25
        } else {
            baseWeight
        }
    }

    override fun getName(): String = "google_biased"
}
