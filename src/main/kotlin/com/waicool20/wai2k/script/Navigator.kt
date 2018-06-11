/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.script

import com.waicool20.wai2k.android.AndroidRegion
import com.waicool20.wai2k.config.Wai2KConfig
import com.waicool20.wai2k.config.Wai2KProfile
import com.waicool20.wai2k.game.GameLocation
import com.waicool20.wai2k.game.GameState
import com.waicool20.wai2k.game.LocationId
import com.waicool20.wai2k.util.cancelAndYield
import com.waicool20.waicoolutils.logging.loggerFor
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.coroutineContext

class Navigator(
        private val gameState: GameState,
        private val region: AndroidRegion,
        private val config: Wai2KConfig,
        private val profile: Wai2KProfile
) {
    private val logger = loggerFor<Navigator>()
    private val locations by lazy { GameLocation.mappings(config, true) }
    /**
     * Finds the current location
     *
     * @return Current [GameLocation]
     */
    suspend fun identifyCurrentLocation(retries: Int = 3): GameLocation {
        logger.info("Identifying current location")
        val channel = Channel<GameLocation?>()
        repeat(retries) {
            val jobs = locations.entries.sortedBy { it.value.isIntermediate }
                    .map { (_, model) ->
                        launch { channel.send(model.takeIf { model.isInRegion(region) }) }
                    }
            channel.consumeEach {
                it?.let { model ->
                    logger.info("GameLocation found: $model")
                    gameState.currentGameLocation = model
                    return model
                }
                if (jobs.all { it.isCompleted }) channel.close()
            }
            logger.warn("Could not find location in after ${it + 1} attempts, retries remaining: ${retries - it - 1}")
        }
        logger.warn("Current location could not be identified")
        coroutineContext.cancelAndYield()
    }

    /**
     * Attempts to navigate to the destination
     *
     * @param destination Name of destination
     */
    suspend fun navigateTo(destination: LocationId) {
        val dest = locations[destination] ?: error("Invalid destination: $destination")
        logger.info("Navigating to ${dest.id}")
        val cLocation = gameState.currentGameLocation.takeIf { it.isInRegion(region) }
                ?: identifyCurrentLocation()
        val path = cLocation.shortestPathTo(dest)
        if (path == null) {
            logger.warn("No known solution from $cLocation to $dest")
            coroutineContext.cancelAndYield()
        }
        if (path.isEmpty()) {
            logger.info("Already at ${dest.id}")
            return
        }
        logger.debug("Found solution: ${path.joinToString("->") { "${it.dest.id}" }}")
        for ((_, destLoc, link) in path) {
            if (gameState.currentGameLocation.isIntermediate && destLoc.isInRegion(region)) {
                continue
            }
            gameState.currentGameLocation = destLoc
            logger.info("Going to ${destLoc.id}")
            // Click the link every 5 seconds, check the region every 500 ms
            var i = 0
            while (!destLoc.isInRegion(region)) {
                if (i++ % 10 == 0) link.asset.getSubRegionFor(region).clickRandomly()
                delay(500)
            }
            logger.info("At ${destLoc.id}")
        }
    }
}