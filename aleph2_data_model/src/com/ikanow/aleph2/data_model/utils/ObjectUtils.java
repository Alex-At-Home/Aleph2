/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.ikanow.aleph2.data_model.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.NonNull;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

/**
 * A set of utilities for access beans/pojos
 * @author acp
 *
 */
public class ObjectUtils {
	
	/**
	 * Enables type-safe access to a single classes
	 * @param clazz - the containing class for the fields
	 * @return a MethodNamingHelper for this class
	 */
	public static <T> MethodNamingHelper<T> from(Class<T> clazz) {
		return new MethodNamingHelper<T>(clazz);
	}
	
	/**
	 * Enables type-safe access to a single classes
	 * @param a - any non-null instance of the class
	 * @return a MethodNamingHelper for this class
	 */
	@SuppressWarnings("unchecked")
	public static <T> MethodNamingHelper<T> from(@NonNull T a) {
		return new MethodNamingHelper<T>((Class<T>) a.getClass());
	}
	
	/** Clones the specified object, returning a builder that can be used to replace specified values
	 * @param the object to clone
	 * @return Clone Helper, finish with done() to return the class
	 */
	public static <T> CloningHelper<T> clone(@NonNull T a) {
		return new CloningHelper<T>(a);
	}
	/**Builds an immutable object using the specified value just to get the class (see clone to actually use the input variable)
	 * @param a - the object determining the class to use
	 * @return Clone Helper, finish with done() to return the class
	 */
	public static <T> CloningHelper<T> build(@NonNull T a) {
		try {
			return new CloningHelper<T>(a.getClass());
		} catch (Exception e) {
			throw new RuntimeException("CloningHelper.build", e);
		}
	}
	/**Builds an immutable object of the specified class
	 * @param a - the class to use
	 * @return Clone Helper, finish with done() to return the class
	 */
	public static <T> CloningHelper<T> build(@NonNull Class<T> clazz) {
		try {
			return new CloningHelper<T>(clazz);
		} catch (Exception e) {
			throw new RuntimeException("CloningHelper.build", e);
		}
	}	
	
	public static class CloningHelper<T> {
		/**Set a field in a cloned/new object
		 * @param fieldName The field to set
		 * @param val the value to which it should be set
		 * @return
		 */
		public <U> CloningHelper<T> with(String fieldName, U val) {
			try {
				Field f = _element.getClass().getDeclaredField(fieldName);
				f.set(_element, val);
			}
			catch (Exception e) {
				throw new RuntimeException("CloningHelper", e);
			}
			return this;
		}
		
		/**Set a field in a cloned/new object
		 * @param fieldName The field to set
		 * @param val the value to which it should be set
		 * @return
		 */
		public <U> CloningHelper<T> with(Function<T, ?> getter, U val) {
			try {
				if (null == _naming_helper) {
					_naming_helper = from(_element);
				}
				Field f = _element.getClass().getDeclaredField(_naming_helper.field(getter));
				f.set(_element, val);
			}
			catch (Exception e) {
				throw new RuntimeException("CloningHelper", e);
			}
			return this;
		}		
		
		/** Finishes the building/cloning process
		 * @return the final version of the element
		 */
		public T done() {
			return _element;
		}
		
		@SuppressWarnings("unchecked")
		protected static Object immutabilizeContainer(Object o) {
			return Patterns.matchAndReturn(o)
					.when(SortedSet.class, c -> Collections.unmodifiableSortedSet(c) )
					.when(Set.class, c -> Collections.unmodifiableSet(c) )
					.when(NavigableMap.class, c -> Collections.unmodifiableNavigableMap(c) )
					.when(SortedMap.class, c -> Collections.unmodifiableSortedMap(c) )
					.when(Map.class, c -> Collections.unmodifiableMap(c) )
					.when(List.class, c -> Collections.unmodifiableList(c) )
					.when(Collection.class, c -> Collections.unmodifiableCollection(c) )
					.otherwise(o);
		}
		
		protected void cloneInitialFields(T to_clone) {
			Arrays.stream(_element.getClass().getDeclaredFields())
				.map(f -> { try { return Tuples._2(f, f.get(_element)); } catch (Exception e) { return null; } })
				.filter(t -> (null != t) && (null != t._2()))
				.forEach(t -> { try { t._1().set(_element, immutabilizeContainer(t._2())); } catch (Exception e) { } } );
		}
		@SuppressWarnings("unchecked")
		protected CloningHelper(Class<?> element_clazz) throws InstantiationException, IllegalAccessException {
			_element = (T) element_clazz.newInstance();
		}
		protected CloningHelper(T to_clone) {
			_element = to_clone;
		}
		protected final T _element;
		protected MethodNamingHelper<T> _naming_helper = null;
	}
	
	/**
	 * A helper class that enables type safe field specification
	 * Note: depends on all accessors being in the format "_<fieldname>()" for the given <fieldname>  
	 * @author acp
	 *
	 * @param <T>
	 */
	public static class MethodNamingHelper<T> implements MethodInterceptor {
		
		protected String _name;
		protected T _recorder;
		@SuppressWarnings("unchecked")
		protected MethodNamingHelper(Class<T> clazz) {
			Enhancer enhancer = new Enhancer();
			enhancer.setSuperclass(clazz);
			enhancer.setCallback(this);
			_recorder = (T) enhancer.create();
		}
		@Override
		public Object intercept(Object object, Method method, Object[] args,
				MethodProxy proxy) throws Throwable
		{
			if (method.getName().equals("field")) {
				return _name;
			}
			else {
				_name = method.getName().substring(1);
			}
			return null;
		}
		/**
		 * @param getter - the method reference (T::<function>)
		 * @return
		 */
		public String field(Function<T, ?> getter) {
			getter.apply(_recorder);
			return _name;
		}
	}
}