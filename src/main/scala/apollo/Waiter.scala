package apollo

import scala.concurrent.ops._

trait Waiter extends Lang {

  def initWaiter:Body[Unit] = for (
		_ <- { spawn {
			run(for(
				waiter <- onMsgThread[Int,Unit,Unit]((howlong,out,_) =>
					{ Thread.sleep(howlong);  out!() }).actor();
				_ <- put("waiter", waiter)
			  ) yield ());
		    }; nothing };
		  // TODO this is a bit lame...
		  // sleep for 10 millis to allow the previously spawned thread time to
		  // register the waiter in the enviroment
		_ <- { Thread.sleep(10); nothing }
	) yield ()
  def waitFor(millis:Int):Body[Unit] = for (
		Some(waiter:Actor[Int,Unit]) <- get("waiter");
		_ <- waiter!?millis
  	) yield ()

  // initialiser
  run(initWaiter)
}

object Waiter extends Evaluator with EmptyInitialEnvironment with Waiter 


