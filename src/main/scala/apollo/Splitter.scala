package apollo

trait Splitter extends Lang {
  type Col[A] = Ref[List[Stream[A]]] 
  def split[A](f:A=>Int):StrF[Stream[A],Col[A]] = strf { (as:Stream[A]) => for(
  		streams <- newRef[List[Stream[A]]](List());
		val theStrf = onMsg[A,Unit,Unit]((a,_,_) => for (
				ss <- streams!?Read();
				// TODO finish this...
				_<-nothing
			) yield ()).strf(());
		_ <- theStrf -< as
  	) yield streams
  }
}
