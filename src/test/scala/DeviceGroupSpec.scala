import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{TestKit, TestProbe}

import scala.concurrent.duration._
import scala.language.postfixOps

class DeviceGroupSpec(_system: ActorSystem)
    extends TestKit(_system)
    with Matchers
    with WordSpecLike
    with BeforeAndAfterAll {
  def this() = this(ActorSystem("DeviceSpec"))

  override def afterAll: Unit = shutdown(system)

  "DeviceGroup actor" must {

    "be able to register a device actor" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender
      deviceActor1 should !==(deviceActor2)

      deviceActor1.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
      deviceActor2.tell(Device.RecordTemperature(requestId = 1, 2.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))
    }

    "ignore requests for wrong groupId" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("wrongGroup", "device1"), probe.ref)
      probe.expectNoMsg(500.milliseconds)
    }

    "return same actor for same deviceId" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      deviceActor1 should ===(deviceActor2)
    }

    "be able to list active devices" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 0), probe.ref)
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))
    }

    "be able to list active devices after one shuts down" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val toShutDown = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 0), probe.ref)
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))

      probe.watch(toShutDown)
      toShutDown ! PoisonPill
      probe.expectTerminated(toShutDown)

      probe.awaitAssert {
        groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 1), probe.ref)
        probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 1, Set("device2")))
      }
    }

    "be able to collect temperatures from all active devices" in {
      val probe      = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device3"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor3 = probe.lastSender

      deviceActor1.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
      deviceActor2.tell(Device.RecordTemperature(requestId = 1, 2.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))

      groupActor.tell(DeviceGroup.RequestAllTemperatures(requestId = 0), probe.ref)
      probe.expectMsg(
        DeviceGroup.RespondAllTemperatures(
          requestId = 0,
          temperatures = Map("device1" -> DeviceGroup.Temperature(1.0),
                             "device2" -> DeviceGroup.Temperature(2.0),
                             "device3" -> DeviceGroup.TemperatureNotAvailable)
        )
      )
    }
  }
}
