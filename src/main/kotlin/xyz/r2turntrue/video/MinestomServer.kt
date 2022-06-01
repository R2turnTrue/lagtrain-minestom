package xyz.r2turntrue.video

import com.mortennobel.imagescaling.ResampleFilters
import com.mortennobel.imagescaling.ResampleOp
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerLoginEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.GenerationUnit
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.metadata.MapMeta
import net.minestom.server.map.MapColors
import net.minestom.server.map.framebuffers.DirectFramebuffer
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO


val frames = ArrayList<Array<ByteArray>>()
var f = 0

fun colorDistance(c1: Color, c2: Color): Double {
    val red1 = c1.red
    val red2 = c2.red
    val rmean = red1 + red2 shr 1
    val r = red1 - red2
    val g = c1.green - c2.green
    val b = c1.blue - c2.blue
    return Math.sqrt((((512 + rmean) * r * r shr 8) + 4 * g * g + ((767 - rmean) * b * b shr 8)).toDouble())
}

fun findClosestColor(arr: Array<Color>, color: Color): Color {
    return arr.sortedBy { colorDistance(color, it) }.first()
}

fun main() {
    println("Loading frames...")
    File("frames").apply {
        if(!exists())
            mkdir()
        val resizeOp = ResampleOp(128, 128)
        resizeOp.filter = ResampleFilters.getBiCubicFilter() // You can change the filter when you need fast speed to process frames!
        listFiles()?.sortedBy {
            Integer.parseInt(it.nameWithoutExtension.replace("image-", ""))
        }?.forEach { file ->
            println("Processing: ${file.nameWithoutExtension}")
            val rawBi = ImageIO.read(file)
            val bi = resizeOp.filter(rawBi, null)
            val data = (bi.raster.dataBuffer as DataBufferByte).data
            val array = Array(bi.width) { arrayOfNulls<Color>(bi.height) }
            for(row in 0 until bi.height) {
                for(col in 0 until bi.width) {
                    //println(Color(bi.getRGB(col, row)).rgb)
                    //array[row][col] = Color(bi.getRGB(col, row)).rgb.absoluteValue
                    val color = Color(bi.getRGB(col, row))
                    array[row][col] = color
                }
            }
            frames.add(array.map { row ->
                row.map { col ->
                    findClosestColor(MapColors.values().filter { color -> color != MapColors.NONE }.map {
                        Color(it.red(), it.green(), it.blue())
                    }.toTypedArray(), col!!)
                }
            }.map { row ->
                row.map { col ->
                    //MapColors.values().find { color -> Color(color.red(), color.green(), color.blue()).rgb == col }!!
                    MapColors.values().find { color -> Color(color.red(), color.green(), color.blue()).rgb == col.rgb }!!
                        .baseColor()
                }.toByteArray()
            }.toTypedArray())
        }
    }
    println("Starting the server...")
    // Initialization
    val minecraftServer = MinecraftServer.init()
    val instanceManager = MinecraftServer.getInstanceManager()
    // Create the instance
    val instanceContainer = instanceManager.createInstanceContainer()
    // Set the ChunkGenerator
    instanceContainer.setGenerator { unit: GenerationUnit ->
        unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK)
    }
    // Add an event callback to specify the spawning instance (and the spawn position)
    val globalEventHandler = MinecraftServer.getGlobalEventHandler()
    globalEventHandler.addListener(
        PlayerLoginEvent::class.java
    ) { event: PlayerLoginEvent ->
        val player: Player = event.player
        event.setSpawningInstance(instanceContainer)
        player.inventory.addItemStack(ItemStack.of(Material.FILLED_MAP)
            .withMeta(MapMeta.Builder()
                .mapId(1)
                .build()))
        player.respawnPoint = Pos(0.0, 42.0, 0.0)
    }
    MinecraftServer.getCommandManager().register(object: Command("play") {
        init {
            setDefaultExecutor { sender, _ ->
                sender.sendMessage("Started playing")
                //val mapData = MapDataPacket(1, 4, false, false, emptyList(), null)
                val fb = DirectFramebuffer()
                CompletableFuture.runAsync {
                    while(f < frames.size) {
                        val fr = frames[f]
                        for(i in fr.indices) {
                            val row = fr[i]
                            for(j in row.indices) {
                                val col = row[j]
                                fb.set(j, i, col)
                            }
                        }

                        MinecraftServer.getConnectionManager().onlinePlayers.forEach {
                            it.sendPacket(fb.preparePacket(1))
                        }
                        f++
                        Thread.sleep(1000 / 15) // 60 FPS
                    }
                    f = 0
                }
            }
        }
    })
    // Start the server on port 25565
    minecraftServer.start("0.0.0.0", 25565)
}