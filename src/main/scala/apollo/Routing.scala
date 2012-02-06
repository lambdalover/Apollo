package apollo

// a sketch of routing a-la Camel

trait Routing extends Lang {

  class Topic[A](env:Env) {
    private val mypipe:(Sink[A],Stream[A]) = run(for(p<-pipe[A]) yield p,env)
    def pub:StrF[Stream[A], Unit] = strf { (as:Stream[A]) => for (
		_ <- as.listen(mypipe._1)
	) yield ()
    }
    def sub:StrF[Unit, Stream[A]] = strf { (_:Unit) => result(mypipe._2) }
  }
  def newTopic[A]:Body[Topic[A]] = for (e<-getEnv) yield new Topic(e)
}

object Routing extends Evaluator with EmptyInitialEnvironment with Routing 

trait RoutingByName extends Lang with Routing {

  def newTopicName[A](name:String):Body[Topic[A]] = for (
		newT <-newTopic[A];
		_ <- put("topic:"+name, newT)
	  ) yield newT
  private def getTopicByName[A](name:String):Body[Topic[A]] = for (
	env <- getEnv;
	t <- env.get("topic:"+name);
	topic <- if (t != null && t.isInstanceOf[Topic[A]]) {
			result(t.asInstanceOf[Topic[A]]) 
		} else {
			newTopicName(name):Body[Topic[A]]
		}
  	) yield topic
  def into[A](name:String):StrF[Stream[A],Unit] = strf { (a:Stream[A]) => for (
		topic <- getTopicByName[A]("topic:"+name);
		_ <- topic.pub -< a
	  ) yield ()
  }
  def from[A](name:String):StrF[Unit,Stream[A]] = strf { (_:Unit) => for (
		topic <- getTopicByName[A]("topic:"+name);
		a <- topic.asInstanceOf[Topic[A]].sub -< ()
	  ) yield a
  }

  def newProcessorName[A,B](name:String, f:StrF[A,B]):Body[Unit] = for (
		env <- getEnv;
		_ <- env.put("processor:"+name, f)
	  ) yield ()
  def process[A,B](name:String):StrF[A,B] = strf { (a:A) => for (
		env <- getEnv;
		f <- env.get("processor:"+name);
		// TODO handle null or cast failure
		b <- f.asInstanceOf[StrF[A,B]] -< a
	  ) yield b
  }

  def when[A](f:A=>Boolean):StrF[Stream[A],Stream[A]] = onMsg((a:A,out:Sink[A],_:Unit) => for (
		_<- if (f(a)) out!a else nothing
	) yield ()
  ).strf(())

  // aggregate - what does it mean in Camel?

  // choice - c.b.r.
  //    when, otherwise

  // recipients
  // split
  // throttle
  // process
  // 

  // some test expresions
  def x = from("a/b") >>> when[Int](_ > 7) >>> into("x/y")
}

object RoutingByName extends Evaluator with EmptyInitialEnvironment with Routing with RoutingByName 


