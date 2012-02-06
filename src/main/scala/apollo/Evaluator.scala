package apollo 
import scala.actors._
import scala.actors.Actor._

trait Evaluator extends Lang {

  val lang:Lang = Evaluator

    // TODO document how this env works (attribute of evaluator vs. threading through Body)
  val env:Env

    // TODO need a better name than MyEnv
  class MyEnv(val env:Env, val eventBasedConcurrency:Boolean) 

    // This is a continuation monad combined with a state monad
    // TODO use a monad transformer for this
  class Body[+A](val in:((A=>MyEnv=>Any)=>MyEnv=>Any)) extends BodyTrait[A] {
    def map[B](f:A => B):Body[B] = flatMap(a=> result(f(a)))
    def flatMap[B](f:A => Body[B]):Body[B]= new Body[B](k => e1 => in(a => e2 => f(a).in(k)(e2))(e1) )
    	// XXX surely failure needs to be handled more gracefully...
    def filter(f:A => Boolean):Body[A] = 
	this // TODO this aint right!!!
	//new Body((e:Env)=> (if (f(r(e))) this else throw new Exception("strange filter condition in Body")))
  }
  implicit def result[A](a:A):Body[A] = new Body[A]( k => e => k(a)(e) )

  implicit def strf[A,B](f:A => Body[B]):StrF[A,B] = new StrF[A,B] {
    def -<(as:A) : Body[B] = f(as)
  }

    // TODO this should probably be written in terms of callcc
  def rcv[A](ac:scala.actors.OutputChannel[A], cond:A=>Boolean):Body[A] = new Body[A](k => e => {
		if (e.eventBasedConcurrency) {
			react {
				case ac!(a:A) if cond(a)  => k(a)(e)
			}
	        } else {
			receive { // thread based concurrency
				case ac!(a:A)  if cond(a) => k(a)(e)
			}
		}
	  } )

  class EvalOnMsg[S,A,B](eventBasedConcurrency:Boolean, on:(A,Sink[B],S)=>Body[S]) extends OnMsg[A,B,S] {
    def actor(init:S):Body[Actor[A,B]] = result(new Actor[A,B] {
  	    val ignoreSink = new Sink[B] { def!(b:B):Body[Unit] = nothing }
	    val ac:scala.actors.Actor = scala.actors.Actor.actor {
		var state = init
		if (eventBasedConcurrency) {
			loop { react {
				case (a:A,sink:Sink[B]) => {
					  // TODO this is probably the wrong 'env' when invoked from within 'withenv'
					state = run(on(a,sink,state),eventBasedConcurrency,env)
				}
			} }
	        } else {
			loop { receive { // thread based concurrency
				case (a:A,sink:Sink[B]) => {
					state = run(on(a,sink,state),eventBasedConcurrency,env)
				}
			} }
		}
	    }
	    def !(a:A):Body[Unit] = result(ac!(a,ignoreSink)) 
	    //def !?(a:A):Body[B] = result((ac!?a).asInstanceOf[B])
	    def !?(a:A):Body[B] = {
		val sink = new Sink[B] {
			val chan = new Channel[B]
			def !(b:B) = result(chan!b)
		} 
	    	for (
			_ <- { (ac!((a,sink))); nothing };
			b:B <- rcv[B](sink.chan, (_:B) => true)
	        ) yield b
	    }
    })
    	// TODO lots of code duplication with actor above
    def strf(init:S):StrF[Stream[A],Stream[B]] = new StrF[Stream[A],Stream[B]] {
	    def -<(as:Stream[A]) : Body[Stream[B]] = for (
	    	(bsink, bstream) <- pipe[B];
		env <- getEnv;
	        val ac:scala.actors.Actor = scala.actors.Actor.actor {
		    var state = init
		    if (eventBasedConcurrency) {
			    loop { react {
				case a:A => {
					state = run(on(a,bsink,state),eventBasedConcurrency,env)
				}
			    } }
		    } else {
			    loop { receive {
				case a:A => {
					state = run(on(a,bsink,state),eventBasedConcurrency,env)
				}
			    } }
		    }
	        };
		val aSink = new Sink[A] {
		    def !(a:A):Body[Unit] =  result(ac!a)
		};
		_<-as.listen(aSink)
	    ) yield bstream
    }
  }

  def onMsg[A,B,S](on:(A, Sink[B],S) => Body[S]):OnMsg[A,B,S] = 
		new EvalOnMsg(true, on)
  def onMsgThread[A,B,S](on:(A, Sink[B], S) => Body[S]):OnMsg[A,B,S] = 
		new EvalOnMsg(false, on)

	// TODO this is incredibly inefficient - for each message
	// we're sending a message to an external state actor and awaiting a response
  class PipeStream[A] extends Stream[A] with Sink[A] {
    private val subState:Body[Ref[List[Sink[A]]]] = newRef(List[Sink[A]]())
    private val msgAc:Body[Actor[A, Unit]] = 
    	onMsg((a:A,_:Sink[Unit],_:Unit) => for (
			subRef <- subState;
			subs <- readRef(subRef);
			_<- subs.foldLeft(nothing)((prev,s:Sink[A])=>
				for(_<-prev;_<-s!a) yield ())
		) yield ()
	).actor(())
    def listen(sink:Sink[A]):Body[Unit] = for (
		subRef <- subState;
		_<-subRef!Update(sink::_) 
	) yield ()
    def !(a:A):Body[Unit] =  for (
		ac <- msgAc;
		_ <- ac!a
    	) yield ()
  }  
  def pipe[A]:Body[(Sink[A], Stream[A])] = 
		{ val s = new PipeStream[A]; result((s,s)) }

    // TODO this should probably be written in terms of callcc
  def getMyEnv:Body[MyEnv] = new Body[MyEnv](k=>e=>k(e)(e))
  def getEnv:Body[Env] = for (me<-getMyEnv) yield me.env
  def getIsEventBasedConcurrency:Body[Boolean] = for (me<-getMyEnv) yield me.eventBasedConcurrency
	// TODO can just provide the whole immutable Map interface?
  def newEnv = new Env {
	private val mapRefState: Body[Ref[Map[String,Any]]] = 
		newRef(scala.collection.immutable.HashMap.empty[String,Any])
  	def get(name:String):Body[Option[Any]] = for (
			mapRef <- mapRefState;
			Res(a:Option[Any]) <- mapRef!?Invoke(_.get(name))
		) yield a
	def put(name:String,a:Any):Body[Unit] = for (
			mapRef <- mapRefState;
			_<- mapRef!Update(_+((name,a)))
		) yield ()
  }

  def idCont[A]:A=>MyEnv=>A = a => e => a
    // TODO refactor this - code duplication between run/2 and withEnv
    // TODO this should probably be written in terms of callcc
  def run[A](body:Body[A], eventBasedConcurrency:Boolean):A = 
  	{ body.in(idCont)(new MyEnv(env, eventBasedConcurrency)).asInstanceOf[A] }
    // is this right? shouldn't the continuation be threaded through?
  def withEnv[A](body:Body[A], e:Env):Body[A] = for(
		eventBased <- getIsEventBasedConcurrency;
		val me = new MyEnv(e, eventBased)
	) yield body.in(idCont)(me).asInstanceOf[A]
 
	// TODO val env:Env = e doesn't work for some reason...
  def getExecutionContext:Body[Lang] = for (
		e <- getEnv
	) yield new Evaluator { val env:Env = UNDEFINED }
}

trait EmptyInitialEnvironment extends Lang {
  val env:Env = newEnv
}

object Evaluator extends Evaluator with EmptyInitialEnvironment

