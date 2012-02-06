package apollo

trait Lang {

    // TODO this is just a standard monad
  type Body[+A] <: BodyTrait[A]
  trait BodyTrait[+A] {
    def map[B](f:A => B):Body[B]
    def flatMap[B](f:A => Body[B]):Body[B]
    def filter(f:A => Boolean):Body[A]
    def then[B](bstate:Body[B]):Body[B] = for (
    	_<-this;
	b<-bstate
    ) yield b
  }
  implicit def result[A](a:A):Body[A]
  val nothing:Body[Unit] = result(())

  trait StrF[A,B] {

    	// bind the inputs of a StrF
    def -<(as:A) : Body[B]

        // some handy utility combinators
    def >>>[C](f:StrF[B,C]) : StrF[A,C] = 
    	strf ( (a:A) => for (
			b <- this -< a;
			c <- f -< b
		) yield c
	)
    def &&&[C](f:StrF[A,C]) : StrF[A, (B,C)] = 
    	strf ( (a:A) => for (
			b <- this -< a;
			c <- f -< a
		) yield (b,c)
	)
    def fst[C]: StrF[(A,C), (B,C)] = 
    	strf { case (a,c) => for (
			b <- this -< a
		) yield (b,c)
	}
  }

  implicit def strf[A,B](f:A => Body[B]):StrF[A,B]

  	// complete construction of f before allowing any events in through as
  def atomic[A,B](f:StrF[Stream[A],B]):StrF[Stream[A],B] = strf { (as:Stream[A]) =>
  	for(
    		(asink, newas) <- pipe[A];
		b <- f -< newas;
		_ <- as.listen(asink)  // open the floodgates
	) yield b
  }

  // TODO is this variance right?
  trait Sink[-A] {
    def !(a:A):Body[Unit]
  }
  trait Actor[-A,+B] extends Sink[A] {
    def !?(a:A):Body[B]
  }

  trait Stream[A] {
    def listen(sink:Sink[A]):Body[Unit]
  }
  trait OnMsg[A,B,S] {
  	def actor(s:S):Body[Actor[A,B]]
  	def strf(s:S):StrF[Stream[A],Stream[B]]
  }

  def onMsg[A,B,S](on:(A, Sink[B],S) => Body[S]):OnMsg[A,B,S]
  def onMsgThread[A,B,S](on:(A, Sink[B], S) => Body[S]):OnMsg[A,B,S]
  //def strf3[A,B](a:Actor[A,B]):StrF[Stream[A],Stream[B]]
  def pipe[A]:Body[(Sink[A], Stream[A])]

  def ignore[A]:StrF[A,Unit] = strf[A,Unit](a => nothing)
  def map[A,B](f:A=>B):StrF[Stream[A],Stream[B]] = 
  	onMsg((a:A,out:Sink[B],_:Unit) => out!(f(a))).strf(())
  def doEach[A](f:A=>Body[Unit]):StrF[Stream[A],Unit] = 
  	onMsg[A,Nothing,Unit]((a,_,_) => f(a)).strf(()) >>> ignore

  def eval(body:Body[Unit]):Body[Unit] = for (
  	ac <- onMsg((_:Unit,_:Sink[Unit],_:Unit) => for (_<-body) yield ()).actor(());
	_<-ac!()
  ) yield ()
  def evalThread(body:Body[Unit]):Body[Unit] = for (
  	ac <- onMsgThread((_:Unit,_:Sink[Unit],_:Unit) => for (_<-body) yield ()).actor(());
	_<-ac!()
  ) yield ()

  def evalF[A](f:StrF[Unit,A]):Body[Unit] = for(_<-f-< ()) yield ()

  type Ref[A] = Actor[RefRequest[A], RefResponse[A]]

  def newRef[A](init:A):Body[Ref[A]] = onMsg(
  	(req:RefRequest[A], out:Sink[RefResponse[A]],cur:A) => req match {
		case Read() => for(_<-out!Val(cur)) yield cur
		case Update(f) => for(_<-out!Ack()) yield f(cur)
		case Invoke(f) => for(_<-out!Res(f(cur))) yield cur
	}
  ).actor(init)
  def readRef[A](ref:Ref[A]):Body[A] = for(
  	Val(a) <- ref!?Read()
  ) yield a
  def updateRef[A](ref:Ref[A],f:A=>A):Body[Unit] =  ref!Update(f)

  abstract class RefRequest[A]
  case class Read[A]() extends RefRequest[A]
  case class Update[A](f:A=>A) extends RefRequest[A]
  case class Invoke[A,B](f:A=>B) extends RefRequest[A]

  abstract class RefResponse[A]
  case class Val[A](a:A) extends RefResponse[A]
  case class Res[A,B](b:B) extends RefResponse[A]
  case class Ack[A]() extends RefResponse[A]

  trait Env {
  	def get(name:String):Body[Option[Any]]
	def put(name:String,a:Any):Body[Unit]
  }

    // TODO run is just eval lifted into the Body monad
  def run[A](body:Body[A]):A = run(body,false)
  def run[A](body:Body[A], eventBasedConcurrency:Boolean):A 
  def run[A](body:Body[A], eventBasedConcurrency:Boolean, env:Env):A = run(withEnv(body,env), eventBasedConcurrency)
  def run[A](body:Body[A], env:Env):A = run(withEnv(body,env))

  def withEnv[A](body:Body[A], env:Env):Body[A] 

  def getExecutionContext:Body[Lang]

    // TODO newEnv should be in a separate trait
  def newEnv:Env
  def getEnv:Body[Env]

    // TODO Lang extends Env? These two methods are just a copy of the Env trait.
  def get(name:String):Body[Option[Any]] = for (
  		env <- getEnv;
		a <- env.get(name)
  	) yield a
  def put(name:String, a:Any):Body[Unit] = for (
  		env <- getEnv;
		_<-env.put(name,a)
  	) yield ()

    // Just here for convenience...
  def UNDEFINED[T]:T = error("undefined")
  def debug(msg:String):Body[Unit] = { println(msg); nothing }

  def rcv[A](ac:scala.actors.OutputChannel[A], cond:A=>Boolean):Body[A] 
}

// TODO
// rewrite onMsg
//   state machine?
//   stream.rcv
// more efficient evaluator
// remote invoker
// concurrency
// futures. (rewrite &&& as future)

// Queues
// Exceptions
// Watch state
// setEnv(?)
// on vs. loop
// run & Env shenanigans into separate ExecutionContext trait?
// implicit strf?

// use cases:
//   labs epn interface
//   service lookup
//   algebra
//   decoration
//   stream splitting
//   patterns on stream
//   customer workflow
//   Sogei workflow



