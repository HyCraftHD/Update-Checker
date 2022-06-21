package net.hycrafthd.update_checker;

public interface Version<T> extends Comparable<Version<?>> {
	
	T getImpl();
	
}
