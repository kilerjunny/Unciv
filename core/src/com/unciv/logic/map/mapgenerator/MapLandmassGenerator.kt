package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.*
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MapLandmassGenerator(
    private val tileMap: TileMap,
    ruleset: Ruleset,
    private val randomness: MapGenerationRandomness
) {
    //region Fields
    private val landTerrainName = getInitializationTerrain(ruleset, TerrainType.Land)
    private val waterTerrainName: String = try {
        getInitializationTerrain(ruleset, TerrainType.Water)
    } catch (_: Exception) {
        landTerrainName
    }
    private val landOnlyMod: Boolean = waterTerrainName == landTerrainName
    private var waterThreshold = 0.0
    //endregion

    companion object {
        // this is called from TileMap constructors as well
        internal fun getInitializationTerrain(ruleset: Ruleset, type: TerrainType) =
            ruleset.terrains.values.firstOrNull { it.type == type && !it.hasUnique(UniqueType.NoNaturalGeneration) }?.name
                ?: throw Exception("Cannot create map - no $type terrains found!")
    }

    fun generateLand() {
        // This is to accommodate land-only mods
        if (landOnlyMod) {
            for (tile in tileMap.values) {
                tile.baseTerrain = landTerrainName
            }
            return
        }
        
        waterThreshold = tileMap.mapParameters.waterThreshold.toDouble()

        when (tileMap.mapParameters.type) {
            MapType.pangaea -> createPangaea()
            MapType.innerSea -> createInnerSea()
            MapType.continentAndIslands -> createContinentAndIslands()
            MapType.maritimeContinents -> createMaritimeContinents()
            MapType.twoContinents -> createTwoContinents()
            MapType.threeContinents -> createThreeContinents()
            MapType.fourCorners -> createFourCorners()
            MapType.archipelago -> createArchipelago()
            MapType.perlin -> createPerlin()
            MapType.fractal -> createFractal()
            MapType.lakes -> createLakes()
            MapType.boreal -> createBoreal()
            MapType.smallContinents -> createSmallContinents()
            MapType.spiral -> createSpiral()
        }

        if (tileMap.mapParameters.shape == MapShape.flatEarth) {
            generateFlatEarthExtraWater()
        }
    }

    private fun generateFlatEarthExtraWater() {
        for (tile in tileMap.values) {
            val isCenterTile = tile.latitude == 0 && tile.longitude == 0
            val isEdgeTile = tile.neighbors.count() < 6

            if (!isCenterTile && !isEdgeTile) continue

            /*
            Flat Earth needs a 3 tile wide water perimeter and a 4 tile radius water center.
            This helps map generators to not place important things there which would be destroyed
            when the ice walls are placed there.
            */
            tile.baseTerrain = waterTerrainName
            for (neighbor in tile.neighbors) {
                neighbor.baseTerrain = waterTerrainName
                for (neighbor2 in neighbor.neighbors) {
                    neighbor2.baseTerrain = waterTerrainName
                    if (!isCenterTile) continue
                    for (neighbor3 in neighbor2.neighbors) {
                        neighbor3.baseTerrain = waterTerrainName
                    }
                }
            }
        }
    }

    private fun spawnLandOrWater(tile: Tile, elevation: Double) {
        tile.baseTerrain = if (elevation < waterThreshold) waterTerrainName else landTerrainName
    }

    /** Repeat [function] until [predicate] is `true`, lowering [waterThreshold] on each retry, preventing an endless loop.
     *  The [predicate] receives the proportion of water on the map, the default accepts <= 70%.
     */
    private fun retryLoweringWaterLevel(predicate: (waterPercent: Float) -> Boolean = { it <= 0.7f }, function: () -> Unit) {
        var retries = 0
        while (++retries <= 30) { // 28 is enough to go from +1 to -1 with only the retries acceleration below
            function()
            val waterPercent = tileMap.values.count { it.baseTerrain == waterTerrainName }.toFloat() / tileMap.values.size
            if (waterThreshold < -1f || predicate(waterPercent)) break

            // lower water table to reduce water percentage, with empiric base step and acceleration
            // (tweaked to acceptable performance on huge maps - but feel free to improve)
            waterThreshold -= 0.02 *
                (waterPercent / 0.7f).coerceAtLeast(1f) *
                retries.toFloat().pow(0.5f)
            //Log.debug("retry %d with waterPercent=%f, waterThreshold=%f", retries, waterPercent, waterThreshold)
        }
    }

    //region Type-specific generators
    private fun createPerlin() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            val elevation = randomness.getPerlinNoise(tile, elevationSeed)
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createFractal() {
        retryLoweringWaterLevel {
            val elevationSeed = randomness.RNG.nextInt().toDouble()
            for (tile in tileMap.values) {
                val maxdim = max(tileMap.maxLatitude, tileMap.maxLongitude)
                var ratio = maxdim / 32.0 // change scale depending on map size so that average number of continents stay the same
                if (tileMap.mapParameters.shape == MapShape.hexagonal || tileMap.mapParameters.shape == MapShape.flatEarth) {
                    ratio *= 0.5 // In hexagonal type map for some reason it tends to make a single continent like pangaea if we don't diminish the scale
                }

                var elevation = randomness.getPerlinNoise(tile, elevationSeed, persistence=0.8, lacunarity=1.5, scale=ratio*30.0)

                elevation += getOceanEdgesTransform(tile)

                spawnLandOrWater(tile, elevation)
            }
        }
    }

    /**
     * A large region that is all tundra and well forested.
     * 
     * Implemented based on screenshot from Civ V: https://i.imgur.com/L63zfv6.jpeg
     * 
     * Notes:
     * - The bottom ~80% is fairly homogenous, with a mix of snow, tundra, plains (when river converts tundra), and a few lakes/small oceans.
     * - There is a band of ocean with ice at the very top.
     * - There is more vegetation and mountains than default.
     */
    private fun createBoreal() {
        // There are two layers of noise
        // The fine noise layer affects lake formation and has no impact on overall elevation
        // The broad layer impacts the coastline and can cause small oceans to spawn inland
        val lakesSeed = randomness.RNG.nextInt().toDouble()
        val broadNoiseSeed = randomness.RNG.nextInt().toDouble() // wavy coast in the north
        
        // Lake frequency is not affected by map size, important for rivers
        val lakesScale = 5.0
        // The broad noise layer scales sublinearly with map size (relatively less noisy on smaller maps)
        val broadNoiseScale = 3.0 * sqrt(tileMap.mapParameters.mapSize.radius.toDouble())
        
        for (tile in tileMap.values) {
            // In range -1.0 to +1.0
            val latitude =
                if (tileMap.mapParameters.shape == MapShape.flatEarth)
                    // North is at the edges instead of at the top
                    2 * MapGenerator.getTileRadius(tile, tileMap) - 1 
                else
                    tile.latitude.toDouble() / tileMap.maxLatitude
            // Elevation declines faster and faster towards the north
            val southElevation = 0.15
            val northElevation = -0.4
            val b = 4
            var elevation = southElevation + (northElevation - southElevation) * E.pow(b * latitude - b)
            
            // The threshold ensures small lakes and rivers spawn irrespective of water level (map parameter)
            val lakeThreshold = 0.43
            val shouldSpawnLake = getRidgedPerlinNoise(tile, lakesSeed, scale=lakesScale) > lakeThreshold
            if (shouldSpawnLake)
                elevation -= 0.5
            
            val broadLayerImpact = 0.55
            elevation += broadLayerImpact * randomness.getPerlinNoise(tile, broadNoiseSeed, scale=broadNoiseScale)
            spawnLandOrWater(tile, elevation)
        }
    }
    
    private fun createSpiral() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        val radius = tileMap.mapParameters.mapSize.radius.toDouble()
        val flipX = if (randomness.RNG.nextBoolean()) -1 else 1
        val flipY = if (randomness.RNG.nextBoolean()) -1 else 1
        val coordinateDivisor = tileMap.width // prevent stretching of spiral
        
        // config
        val spinFactor = 4.5 * sqrt(radius) // how quickly the spiral spins
        val noiseScale = 0.5 * sqrt(radius) // lower means more grainy noise
        
        for (tile in tileMap.values) {
            val coordinate = HexMath.hex2WorldCoords(tile.position)
            val x = coordinate.x / coordinateDivisor * flipX
            val y = coordinate.y / coordinateDivisor * flipY
            
            // spiral function with output range -1 to +1
            var elevation = sin(atan2(y, x) - spinFactor * hypot(x, y))
            
            // config
            elevation *= 0.25 // adjust range
            elevation += 0.15 // water level
            elevation += 0.18 * randomness.getPerlinNoise(tile, elevationSeed, scale=noiseScale)
            
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createLakes() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            val elevation = 0.3 - getRidgedPerlinNoise(tile, elevationSeed, persistence=0.7, lacunarity=1.5)

            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createSmallContinents() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        waterThreshold += 0.25
        retryLoweringWaterLevel {
            for (tile in tileMap.values) {
                var elevation = getRidgedPerlinNoise(tile, elevationSeed, scale = 22.0)
                elevation += getOceanEdgesTransform(tile)
                spawnLandOrWater(tile, elevation)
            }
        }
    }

    private fun createArchipelago() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        waterThreshold += 0.25
        for (tile in tileMap.values) {
            val elevation = getRidgedPerlinNoise(tile, elevationSeed)
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createPangaea() {
        val largeContinentThreshold = 25
            .coerceAtMost(tileMap.values.size / 4)  // Or really tiny maps will exhaust retries
            .coerceAtLeast(tileMap.values.size.toFloat().pow(0.333f).toInt())  // kicks in on really large maps: 130k tiles -> 50
        retryLoweringWaterLevel(predicate = { waterPercent ->
            val largeContinents = tileMap.continentSizes.values.count { it > largeContinentThreshold }
            largeContinents == 1 && waterPercent <= 0.7f
        }) {
            val elevationSeed = randomness.RNG.nextInt().toDouble()
            for (tile in tileMap.values) {
                var elevation = randomness.getPerlinNoise(tile, elevationSeed)
                elevation = elevation * (3 / 4f) + getEllipticContinent(tile) / 4
                spawnLandOrWater(tile, elevation)
                tile.setTerrainTransients() // necessary for assignContinents
            }
            tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign) // to support largeContinents above
        }
        tileMap.assignContinents(TileMap.AssignContinentsMode.Clear)
    }

    private fun createInnerSea() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation -= getEllipticContinent(tile, 0.6) * 0.3
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createContinentAndIslands() {
        val isNorth = randomness.RNG.nextDouble() < 0.5
        val isLatitude =
                if (tileMap.mapParameters.shape == MapShape.hexagonal || tileMap.mapParameters.shape == MapShape.flatEarth) randomness.RNG.nextDouble() > 0.5f
                else if (tileMap.mapParameters.mapSize.height > tileMap.mapParameters.mapSize.width) true
                else if (tileMap.mapParameters.mapSize.width > tileMap.mapParameters.mapSize.height) false
                else randomness.RNG.nextDouble() > 0.5f

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getContinentAndIslandsTransform(tile, isNorth, isLatitude)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    /**
     * GS_MARITIME_WORLD_V1
     * Continent-and-islands geography, deterministic 65% water coverage at the
     * landmass stage, plus bounded small-island and narrow-strait edits.
     * The original map types and their RNG sequences are not changed.
     * This runs once when creating a map, NEVER during game turns.
     */
    private fun createMaritimeContinents() {
        val isNorth = randomness.RNG.nextDouble() < 0.5
        val isLatitude = if (tileMap.mapParameters.shape == MapShape.hexagonal ||
            tileMap.mapParameters.shape == MapShape.flatEarth)
            randomness.RNG.nextDouble() > 0.5
        else if (tileMap.mapParameters.mapSize.height > tileMap.mapParameters.mapSize.width) true
        else if (tileMap.mapParameters.mapSize.width > tileMap.mapParameters.mapSize.height) false
        else randomness.RNG.nextDouble() > 0.5

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        val detailSeed = randomness.RNG.nextInt().toDouble()
        val elevations = tileMap.values.map { tile ->
            val broad = randomness.getPerlinNoise(tile, elevationSeed)
            val mass = getContinentAndIslandsTransform(tile, isNorth, isLatitude)
            val detail = getRidgedPerlinNoise(tile, detailSeed, nOctaves = 4, scale = 17.0)
            val value = broad * 0.46 + mass * 0.48 + detail * 0.06 +
                getOceanEdgesTransform(tile) * 0.12
            tile to value
        }.sortedBy { it.second }

        // Sorting is stable: ties retain TileMap iteration order, and a fixed
        // seed + parameters yield the same landmass on repeated generation.
        // The existing advanced Water level slider remains meaningful:
        // -0.1 means about 55% water, +0.1 about 75% (65% by default).
        val waterRatio = (0.65 + tileMap.mapParameters.waterThreshold.toDouble()).coerceIn(0.55, 0.75)
        val targetWater = (elevations.size * waterRatio).toInt()
        for ((index, entry) in elevations.withIndex())
            entry.first.baseTerrain = if (index < targetWater) waterTerrainName else landTerrainName

        val newIslands = addMaritimeIslandChains()
        carveMaritimeStraits(newIslands)
    }

    /** Bounded 4-tile island groups, generally within five tiles of existing land. */
    private fun addMaritimeIslandChains(): Set<Tile> {
        val createdIslands = HashSet<Tile>()
        val budget = (tileMap.values.size / 320).coerceAtMost(160)
        if (budget < 4) return createdIslands

        val candidates = tileMap.values.filter { it.baseTerrain == waterTerrainName }
            .shuffled(randomness.RNG)
        for (center in candidates) {
            if (createdIslands.size + 4 > budget) break
            if (center.baseTerrain != waterTerrainName) continue
            val neighbors = center.neighbors.toList()
            if (neighbors.size != 6 || neighbors.any { it.baseTerrain != waterTerrainName }) continue
            if (center.getTilesInDistance(5).none { it.baseTerrain == landTerrainName }) continue

            // Compact groups give a better settlement opportunity than isolated
            // single-tile islands, without blocking a full ocean corridor.
            val group = listOf(center) + neighbors.shuffled(randomness.RNG).take(3)
            for (tile in group) {
                tile.baseTerrain = landTerrainName
                createdIslands.add(tile)
            }
        }
        return createdIslands
    }

    /** Open a few one-tile land bridges into potential straits or inlets. */
    private fun carveMaritimeStraits(protectedIslands: Set<Tile>) {
        val maxCuts = (tileMap.values.size / 500).coerceAtMost(90)
        if (maxCuts <= 0) return
        val cuts = HashSet<Tile>()
        val candidates = tileMap.values.filter { it.baseTerrain == landTerrainName && it !in protectedIslands }
            .shuffled(randomness.RNG)
        for (tile in candidates) {
            if (cuts.size >= maxCuts) break
            if (tile.neighbors.count() != 6 || tile.neighbors.any { it in cuts }) continue
            val waterSides = tile.neighbors.filter { it.baseTerrain == waterTerrainName }.toList()
            if (waterSides.size !in 2..4) continue
            // Water on two non-adjacent sides makes this a narrow neck, rather
            // than converting long segments of ordinary coastline into water.
            val spansNeck = waterSides.any { first ->
                waterSides.any { second -> first != second && first.aerialDistanceTo(second) >= 2 }
            }
            if (!spansNeck) continue
            tile.baseTerrain = waterTerrainName
            cuts.add(tile)
        }
    }

    private fun createTwoContinents() {
        val isLatitude =
                if (tileMap.mapParameters.shape == MapShape.hexagonal || tileMap.mapParameters.shape == MapShape.flatEarth) randomness.RNG.nextDouble() > 0.5f
                else if (tileMap.mapParameters.mapSize.height > tileMap.mapParameters.mapSize.width) true
                else if (tileMap.mapParameters.mapSize.width > tileMap.mapParameters.mapSize.height) false
                else randomness.RNG.nextDouble() > 0.5f

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getTwoContinentsTransform(tile, isLatitude)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createThreeContinents() {
        val isNorth = randomness.RNG.nextDouble() < 0.5
        // On flat earth maps we can randomly do East or West instead of North or South
        val isEastWest = tileMap.mapParameters.shape == MapShape.flatEarth && randomness.RNG.nextDouble() > 0.5

        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = (elevation + getThreeContinentsTransform(tile, isNorth, isEastWest)) / 2.0
            spawnLandOrWater(tile, elevation)
        }
    }

    private fun createFourCorners() {
        val elevationSeed = randomness.RNG.nextInt().toDouble()
        for (tile in tileMap.values) {
            var elevation = randomness.getPerlinNoise(tile, elevationSeed)
            elevation = elevation / 2 + getFourCornersTransform(tile) / 2
            spawnLandOrWater(tile, elevation)
        }
    }

    //endregion
    //region Shaping helpers

    /**
     * Create an elevation map that favors a central elliptic continent spanning over 85% - 95% of
     * the map size.
     */
    private fun getEllipticContinent(tile: Tile, percentOfMap: Double = 0.85): Double {
        val randomScale = randomness.RNG.nextDouble()
        val ratio = percentOfMap + 0.1 * randomness.RNG.nextDouble()

        val a = ratio * tileMap.maxLongitude
        val b = ratio * tileMap.maxLatitude
        val x = tile.longitude.toFloat()
        val y = tile.latitude.toFloat()

        val distanceFactor = x * x / (a * a) + y * y / (b * b) + (x+y).pow(2) / (a+b).pow(2)

        return min(0.3, 1.0 - (5.0 * distanceFactor * distanceFactor + randomScale) / 3.0)
    }

    private fun getContinentAndIslandsTransform(tile: Tile, isNorth: Boolean, isLatitude: Boolean): Double {
        // The idea here is to create a water area separating the two land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        var (longitudeFactor, latitudeFactor) = getLonLatFactors(tile)

        // We then pick one side to become islands instead of a continent
        if (isLatitude) {
            if (isNorth && tile.latitude < 0 || !isNorth && tile.latitude > 0)
                latitudeFactor = 0.2f
        } else {
            // In longitude mode North represents West
            if (isNorth && tile.longitude < 0 || !isNorth && tile.longitude > 0)
                longitudeFactor = 0.2f
        }

        var factor = if (isLatitude) latitudeFactor else longitudeFactor

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap)
            factor = min(factor, getReverseLongitudeFactor(tile.longitude)) * 1.1f

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.6f) + randomScale) / 3.0)
    }

    private fun getTwoContinentsTransform(tile: Tile, isLatitude: Boolean): Double {
        // The idea here is to create a water area separating the two land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        val (longitudeFactor, latitudeFactor) = getLonLatFactors(tile)

        var factor = if (isLatitude) latitudeFactor else longitudeFactor

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap)
            factor = min(longitudeFactor, getReverseLongitudeFactor(tile.longitude)) * 1.5f

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.6f) + randomScale) / 3.0)
    }

    private fun getThreeContinentsTransform(tile: Tile, isNorth: Boolean, isEastWest: Boolean): Double {
        // The idea here is to create a water area separating the three land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        var (longitudeFactor, latitudeFactor) = getLonLatFactors(tile)

        // 3rd continent should use only half the map width, or if flat earth, only a third
        val sizeReductionFactor = if (tileMap.mapParameters.shape == MapShape.flatEarth) 3f else 2f

        // We then pick one side to be merged into one centered continent instead of two cornered.
        if (isEastWest) {
            // In EastWest mode North represents West
            if (isNorth && tile.longitude < 0 || !isNorth && tile.longitude > 0)
                latitudeFactor = max(0f, tileMap.maxLatitude - abs(tile.latitude * sizeReductionFactor)) / tileMap.maxLatitude
        } else {
            if (isNorth && tile.latitude < 0 || !isNorth && tile.latitude > 0)
                longitudeFactor = max(0f, getReverseLongitudeFactor(abs(tile.longitude * sizeReductionFactor).toInt()))
        }

        var factor = min(longitudeFactor, latitudeFactor)

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap) {
            factor = min(
                factor,
                getReverseLongitudeFactor(tile.longitude)
            ) * 1.5f
        }

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return min(0.2, -1.0 + (5.0 * factor.pow(0.5f) + randomScale) / 3.0)
    }

    private fun getFourCornersTransform(tile: Tile): Double {
        // The idea here is to create a water area separating the four land areas.
        // So what we do it create a line of water in the middle - where latitude or longitude is close to 0.
        val randomScale = randomness.RNG.nextDouble()
        val (longitudeFactor, latitudeFactor) = getLonLatFactors(tile)
        
        var factor = sqrt(longitudeFactor * latitudeFactor) /1.5f

        // If this is a world wrap, we want it to be separated on both sides -
        // so we make the actual strip of water thinner, but we put it both in the middle of the map and on the edges of the map
        if (tileMap.mapParameters.worldWrap) {
            factor = min(
                factor,
                getReverseLongitudeFactor(tile.longitude)
            ) * 1.5f
        }

        val shouldBeWater = 1-factor

        // there's nothing magical about this, it's just what we got from playing around with a lot of different options -
        //   the numbers can be changed if you find that something else creates better looking continents
        return 1.0 - (5.0 * shouldBeWater*shouldBeWater + randomScale) / 3.0
    }

    private fun getOceanEdgesTransform(tile: Tile): Double {
        // The idea is to reduce elevation at the border of the map, so that we have mostly ocean there.
        val maxX = tileMap.maxLongitude
        val maxY = tileMap.maxLatitude
        val x = tile.longitude
        val y = tile.latitude

        var elevationOffset = 0.0

        val xdistanceratio = abs(x).toDouble() / maxX
        val ydistanceratio = abs(y).toDouble() / maxY

        if (tileMap.mapParameters.shape == MapShape.hexagonal || tileMap.mapParameters.shape == MapShape.flatEarth) {
            val startdropoffratio = 0.8 // distance from center at which we start decreasing elevation linearly

            // On wrapping maps, we must NOT reduce elevation at the East/West edges, otherwise
            // land will never span across the map seam. 
            // We ignore the horizontal (X) ratio and only apply the ocean drop-off to the
            // North/South (Y) poles.
            val distancefromcenter = if (tileMap.mapParameters.worldWrap && tileMap.mapParameters.shape == MapShape.hexagonal) ydistanceratio
            else sqrt(xdistanceratio * xdistanceratio + ydistanceratio * ydistanceratio)

            var distanceoffset = 0.0
            if (distancefromcenter > startdropoffratio) {
                val dropoffdistance = distancefromcenter - startdropoffratio
                val normalizationDivisor = 1.0 - startdropoffratio // for normalizing to [0;1] range
                distanceoffset = dropoffdistance / normalizationDivisor
            }
            elevationOffset -= distanceoffset * 0.35
        } else {

            var xoffset = 0.0

            val xstartdropoffratio = 0.8
            // Only force oceans on the left/right edges of rectangular maps if the world is NOT wrapping.
            if (!tileMap.mapParameters.worldWrap && xdistanceratio > xstartdropoffratio) {
                val xdropoffdistance = xdistanceratio - xstartdropoffratio
                val xnormalizationdivisor = 1.0 - xstartdropoffratio // for normalizing to [0;1] range
                xoffset = xdropoffdistance / xnormalizationdivisor
            }

            var yoffset = 0.0

            val ystartdropoffratio = 0.76 // we want to have enough space at the north and south for circumnavigation
            if (ydistanceratio > ystartdropoffratio) {
                val ydropoffdistance = ydistanceratio - ystartdropoffratio
                val ynormalizationdivisor = 1.0 - ystartdropoffratio // for normalizing to [0;1] range
                yoffset = ydropoffdistance / ynormalizationdivisor
            }
            // these factors were just found by trial and error to be adequate for having enough space while not reducing land too much.
            elevationOffset -= xoffset * 0.33
            elevationOffset -= yoffset * 0.35
        }

        return max(elevationOffset, -0.35)
    }

    /**
     * Generates ridged perlin noise. As for parameters see [MapGenerationRandomness.getPerlinNoise]
     */
    private fun getRidgedPerlinNoise(tile: Tile, seed: Double,
                                     nOctaves: Int = 10,
                                     persistence: Double = 0.5,
                                     lacunarity: Double = 2.0,
                                     scale: Double = 10.0): Double {
        return randomness.getNoise(tile, seed, nOctaves, persistence, lacunarity, scale, Perlin::ridgedNoise3d)
    }
    
    /** Returns lon at lat "percentile from center" - numbers between 0.0-0.1 */
    private fun getLonLatFactors(tile: Tile): Pair<Float, Float>{
        val longitudeFactor = abs(tile.longitude) / tileMap.maxLongitude.toFloat()
        val latitudeFactor = abs(tile.latitude) / tileMap.maxLatitude.toFloat()
        return longitudeFactor to latitudeFactor
    }
    
    /** For world wrap maps, we want the "1-N" factor of the tile */
    private fun getReverseLongitudeFactor(longitude: Int): Float = 
        1 - abs(longitude) / tileMap.maxLongitude.toFloat()
    
    //endregion
}
