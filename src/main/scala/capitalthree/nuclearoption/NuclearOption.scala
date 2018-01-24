package capitalthree.nuclearoption

import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable
import net.minecraft.command.{CommandBase, ICommandSender}
import net.minecraft.entity.player.{EntityPlayer, EntityPlayerMP}
import net.minecraft.server.MinecraftServer
import net.minecraft.util.text.TextComponentString
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.config.Configuration
import net.minecraftforge.fml.common.Mod.EventHandler
import net.minecraftforge.fml.common.event.{FMLPreInitializationEvent, FMLServerStartingEvent}
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent.{PlayerLoggedInEvent, PlayerLoggedOutEvent}
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent
import net.minecraftforge.fml.common.{FMLCommonHandler, Mod}
import org.apache.logging.log4j.Logger

@Mod(modid = "nuclearoption", version = "2.6", name = "NuclearOption", modLanguage = "scala", acceptableRemoteVersions="*")
object NuclearOption {
  var cfg:NukeOptionStrings = null
  var logger:Logger = null
  val votes = new mutable.HashSet[UUID]
  var spamTick = 0 // for rate-limiting global messages
  var tick = 0
  var prevTally = 0f
  var nukeTriggered = false
  var nukeMilli: Long = 0 // the tick that a nuke was launched

  @EventHandler
  def init(e: FMLPreInitializationEvent): Unit = {
    logger = e.getModLog

    cfg = NukeOptionStrings(new Configuration(e.getSuggestedConfigurationFile))

    val nukeSeconds = 60
    val warningSeconds = Array(45, 30, 15, 10, 9, 8, 7, 6, 5, 4, 3, -1)
    var warningPointer = 0

    val tickHandler = new Object {
      @SubscribeEvent
      def onTick(event: ServerTickEvent): Unit = {
        if (event.phase == TickEvent.Phase.START) {
          tick += 1
          if (nukeTriggered) {
            val remainingSec = nukeSeconds - (System.currentTimeMillis() - nukeMilli)/1000
            if (remainingSec <= 0) {
              FMLCommonHandler.instance().getMinecraftServerInstance.initiateShutdown()
            } else {
              while (warningSeconds(warningPointer) >= remainingSec) {
                spamAllPlayers(s"${cfg.timertick} ${warningSeconds(warningPointer)} seconds")
                warningPointer += 1
              }
            }
          }
        }
      }

      @SubscribeEvent
      def join(e: PlayerLoggedInEvent) {
        if (! cfg.welcome.isEmpty) e.player.sendMessage(new TextComponentString(cfg.welcome))
      }

      @SubscribeEvent
      def part(e: PlayerLoggedOutEvent): Unit = {
        votes.remove(e.player.getUniqueID)
        checkVotes()
      }
    }

    MinecraftForge.EVENT_BUS.register(tickHandler)
  }

  @EventHandler
  def start(e: FMLServerStartingEvent): Unit = {
    e.registerServerCommand(NukeCommand)
  }

  def spamAllPlayers(message: String): Unit = {
    val messageComponent = new TextComponentString(message)
    FMLCommonHandler.instance().getMinecraftServerInstance.getPlayerList.getPlayers.foreach(_.sendMessage(messageComponent))
  }

  /**
    * @return whether spam was issued
    */
  def checkVotes(): Boolean = {
    if (nukeTriggered) return false

    val loggedIn = FMLCommonHandler.instance().getMinecraftServerInstance.getPlayerList.getPlayers.map(_.getUniqueID).toSet
    votes.retain(loggedIn)
    val tally = votes.size.toFloat / loggedIn.size

    if (tally > .5) {
      nukeTriggered = true
      nukeMilli = System.currentTimeMillis()
      spamAllPlayers(cfg.timerstart)
      return true
    }

    val ret = if (tally > prevTally && tally >= .1 && tick > spamTick) {
        if (tally >= .25) spamAllPlayers(cfg.p25)
        else spamAllPlayers(cfg.p10)
        spamTick = tick + 900
        true
    } else false

    prevTally = tally

    ret
  }
}

object NukeCommand extends CommandBase {
  override def getName = "nuke"

  override def execute(server: MinecraftServer, sender: ICommandSender, args: Array[String]): Unit = {
    if (NuclearOption.nukeTriggered) return

    val player = sender match {
      case player: EntityPlayer => player
      case _ => return
    }

    if (NuclearOption.tick < NuclearOption.cfg.graceticks) {
      player.sendMessage(new TextComponentString(NuclearOption.cfg.tooearly))
      return
    }

    val nuke = !NuclearOption.votes.remove(player.getUniqueID)
    if (nuke) NuclearOption.votes.add(player.getUniqueID)

    if (! NuclearOption.checkVotes())
      player.sendMessage(new TextComponentString(if (nuke) NuclearOption.cfg.nukeon else NuclearOption.cfg.nukeoff))
  }

  override def getUsage(sender: ICommandSender) = "/nuke"

  override def checkPermission(server: MinecraftServer, sender: ICommandSender): Boolean = sender.isInstanceOf[EntityPlayerMP]
}

case class NukeOptionStrings(c: Configuration) {
  private def opt(name: String, defval: String) = c.get("strings", name, defval).getString
  private def opti(name: String, defval: Integer, comment: String) = c.get("timers", name, defval, comment).getInt
  val welcome: String = opt("welcome", "Greetings!  This minecraft server is equipped with a doomsday device.  If it becomes unstable, use /nuke to vote for a restart.")
  val tooearly: String = opt("tooearly", "We require more vespene gas.")
  val graceticks: Int = 20*opti("tooearly", 600, "Time after a server restart before it can be restarted again (in seconds)")
  val nukeon: String = opt("nukeon", "You are now hailing nukes.")
  val nukeoff: String = opt("nukeoff", "Wait, nevermind!  No nukes here please!")
  val p10: String = opt("10percent", "Ghost reporting.")
  val p25: String = opt("25percent", "Somebody call for an exterminator?")
  val timerstart: String = opt("timerstart", "Nuclear launch detected!  (Save your work!)")
  val timertick: String = opt("timertick", "Nukes will land in")

  if (c.hasChanged) c.save()
}