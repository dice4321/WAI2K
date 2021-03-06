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
import com.waicool20.wai2k.game.LocationId
import com.waicool20.wai2k.util.cancelAndYield
import com.waicool20.waicoolutils.logging.loggerFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.CoroutineContext

class Navigator(
        private val scriptRunner: ScriptRunner,
        private val region: AndroidRegion,
        private val config: Wai2KConfig,
        private val profile: Wai2KProfile
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = scriptRunner.coroutineContext
    private val logger = loggerFor<Navigator>()
    private val gameState get() = scriptRunner.gameState
    private val scriptStats get() = scriptRunner.scriptStats
    private val locations by lazy { GameLocation.mappings(config, true) }
    /**
     * Finds the current location
     *
     * @return Current [GameLocation]
     */
    suspend fun identifyCurrentLocation(retries: Int = 3): GameLocation {
        logger.info("Identifying current location")
        val channel = Channel<GameLocation?>()
        repeat(retries) { i ->
            checkLogistics()
            val jobs = locations.entries.sortedBy { it.value.isIntermediate }
                    .map { (_, model) ->
                        launch { channel.send(model.takeIf { model.isInRegion(region) }) }
                    }
            for (loc in channel) {
                loc?.let { model ->
                    logger.info("GameLocation found: $model")
                    gameState.currentGameLocation = model
                    return model
                }
                if (jobs.all { it.isCompleted }) break
            }
            logger.warn("Could not find location after ${i + 1} attempts, retries remaining: ${retries - i - 1}")
            delay(1000)
        }
        channel.close()
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
        logger.debug("Found solution: CURRENT->${path.joinToString("->") { "${it.dest.id}" }}")
        for ((_, destLoc, link) in path) {
            if (gameState.currentGameLocation.isIntermediate && destLoc.isInRegion(region)) {
                logger.info("At ${destLoc.id}")
                continue
            }
            logger.info("Going to ${destLoc.id}")
            // Click the link every 1.5 seconds, check the region every 300 ms in case the first clicks didn't get it
            var i = 0
            do {
                checkLogistics()
                if (i++ % 5 == 0) link.asset.getSubRegionFor(region).clickRandomly()
                delay(300)
            } while (!destLoc.isInRegion(region))
            logger.info("At ${destLoc.id}")
            gameState.currentGameLocation = destLoc
        }
    }

    /**
     * Checks if there are logistics, if there were then try and receive them
     */
    suspend fun checkLogistics() {
        while (region.has("navigator/logistics_arrived.png")) {
            logger.info("An echelon has arrived from logistics")
            region.clickRandomly(); delay(500)

            // Continue based on receival mode
            val cont = when (profile.logistics.receiveMode) {
                Wai2KProfile.Logistics.ReceivalMode.ALWAYS_CONTINUE -> {
                    logger.info("Continuing this logistics support")
                    true
                }
                Wai2KProfile.Logistics.ReceivalMode.RANDOM -> {
                    if (Random().nextBoolean()) {
                        logger.info("Randomized receive, continue logistics support this time")
                        true
                    } else {
                        logger.info("Randomized receive, stopping logistics support this time")
                        false
                    }
                }
                Wai2KProfile.Logistics.ReceivalMode.ALWAYS_CANCEL -> {
                    logger.info("Stopping this logistics support")
                    false
                }
                else -> error("Got an invalid ReceivalMode for some reason")
            }
            val image = if (cont) {
                // Increment sent stats if we are continuing
                scriptStats.logisticsSupportSent++
                "confirm.png"
            } else "cancel.png"

            region.waitSuspending(image, 10)?.clickRandomly()
            scriptStats.logisticsSupportReceived++

            // Mark game state dirty, needs updating
            gameState.requiresUpdate = true
            // Wait a bit in case another echelon arrives
            logger.info("Waiting a bit to see if anymore echelons arrive")
            delay(5000)
        }
    }
}