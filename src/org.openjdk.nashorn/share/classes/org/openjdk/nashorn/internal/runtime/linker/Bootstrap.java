/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.nashorn.internal.runtime.linker;

import static org.openjdk.nashorn.internal.codegen.CompilerConstants.staticCallNoLookup;
import static org.openjdk.nashorn.internal.runtime.ECMAErrors.typeError;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.List;
import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.DynamicLinker;
import jdk.dynalink.DynamicLinkerFactory;
import jdk.dynalink.beans.BeansLinker;
import jdk.dynalink.beans.StaticClass;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.GuardingDynamicLinker;
import jdk.dynalink.linker.LinkRequest;
import jdk.dynalink.linker.LinkerServices;
import jdk.dynalink.linker.MethodTypeConversionStrategy;
import jdk.dynalink.linker.TypeBasedGuardingDynamicLinker;
import jdk.dynalink.linker.support.TypeUtilities;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.openjdk.nashorn.internal.codegen.CompilerConstants.Call;
import org.openjdk.nashorn.internal.lookup.MethodHandleFactory;
import org.openjdk.nashorn.internal.lookup.MethodHandleFunctionality;
import org.openjdk.nashorn.internal.runtime.Context;
import org.openjdk.nashorn.internal.runtime.ECMAException;
import org.openjdk.nashorn.internal.runtime.JSType;
import org.openjdk.nashorn.internal.runtime.OptimisticReturnFilters;
import org.openjdk.nashorn.internal.runtime.ScriptFunction;
import org.openjdk.nashorn.internal.runtime.ScriptRuntime;

/**
 * This class houses bootstrap method for invokedynamic instructions generated by compiler.
 */
public final class Bootstrap {
    /** Reference to the seed boostrap function */
    public static final Call BOOTSTRAP = staticCallNoLookup(Bootstrap.class, "bootstrap", CallSite.class, Lookup.class, String.class, MethodType.class, int.class);

    private static final MethodHandleFunctionality MH = MethodHandleFactory.getFunctionality();

    private static final MethodHandle VOID_TO_OBJECT = MH.constant(Object.class, ScriptRuntime.UNDEFINED);

    private static final BeansLinker beansLinker = new BeansLinker(Bootstrap::createMissingMemberHandler);
    private static final GuardingDynamicLinker[] prioritizedLinkers;
    private static final GuardingDynamicLinker[] fallbackLinkers;

    static {
        final NashornBeansLinker nashornBeansLinker = new NashornBeansLinker(beansLinker);
        prioritizedLinkers = new GuardingDynamicLinker[] {
            new NashornLinker(),
            new NashornPrimitiveLinker(),
            new BoundCallableLinker(),
            new JavaSuperAdapterLinker(beansLinker),
            new JSObjectLinker(nashornBeansLinker),
            new BrowserJSObjectLinker(nashornBeansLinker),
            new ReflectionCheckLinker()
        };
        fallbackLinkers = new GuardingDynamicLinker[] {
            new NashornStaticClassLinker(beansLinker),
            nashornBeansLinker,
            new NashornBottomLinker()
        };
    }

    // do not create me!!
    private Bootstrap() {
    }

    /**
     * Returns a list of exposed nashorn dynalink linkers.
     *
     * @return a list of exposed nashorn dynalink linkers.
     */
    public static List<GuardingDynamicLinker> getExposedLinkers() {
        // we have to create BeansLinker without nashorn specific missing member handler!
        // Or else, we'd return values such as 'undefined' to the external world!
        final NashornBeansLinker nbl = new NashornBeansLinker(new BeansLinker());
        final JSObjectLinker linker = new JSObjectLinker(nbl);
        return Collections.singletonList(linker);
    }

    /**
     * Creates a Nashorn dynamic linker with the given app class loader.
     * @param appLoader the app class loader. It will be used to discover
     * additional language runtime linkers (if any).
     * @param unstableRelinkThreshold the unstable relink threshold
     * @return a newly created dynamic linker.
     */
    public static DynamicLinker createDynamicLinker(final ClassLoader appLoader,
            final int unstableRelinkThreshold) {
        final DynamicLinkerFactory factory = new DynamicLinkerFactory();
        factory.setPrioritizedLinkers(prioritizedLinkers);
        factory.setFallbackLinkers(fallbackLinkers);
        factory.setSyncOnRelink(true);
        factory.setPrelinkTransformer((inv, request, linkerServices) -> {
            final CallSiteDescriptor desc = request.getCallSiteDescriptor();
            return OptimisticReturnFilters.filterOptimisticReturnValue(inv, desc).asType(linkerServices, desc.getMethodType());
        });
        factory.setAutoConversionStrategy(Bootstrap::unboxReturnType);
        factory.setInternalObjectsFilter(NashornBeansLinker.createHiddenObjectFilter());
        factory.setUnstableRelinkThreshold(unstableRelinkThreshold);

        // Linkers for any additional language runtimes deployed alongside Nashorn will be picked up by the factory.
        factory.setClassLoader(appLoader);
        return factory.createLinker();
    }

    /**
     * Returns a dynamic linker for the specific Java class using beans semantics.
     * @param clazz the Java class
     * @return a dynamic linker for the specific Java class using beans semantics.
     */
    public static TypeBasedGuardingDynamicLinker getBeanLinkerForClass(final Class<?> clazz) {
        return beansLinker.getLinkerForClass(clazz);
    }

    /**
     * Returns if the given object is a "callable"
     * @param obj object to be checked for callability
     * @return true if the obj is callable
     */
    public static boolean isCallable(final Object obj) {
        if (obj == ScriptRuntime.UNDEFINED || obj == null) {
            return false;
        }

        return obj instanceof ScriptFunction ||
            isJSObjectFunction(obj) ||
            BeansLinker.isDynamicMethod(obj) ||
            obj instanceof BoundCallable ||
            isFunctionalInterfaceObject(obj) ||
            obj instanceof StaticClass;
    }

    /**
     * Returns true if the given object is a strict callable
     * @param callable the callable object to be checked for strictness
     * @return true if the obj is a strict callable, false if it is a non-strict callable.
     * @throws ECMAException with {@code TypeError} if the object is not a callable.
     */
    public static boolean isStrictCallable(final Object callable) {
        if (callable instanceof ScriptFunction) {
            return ((ScriptFunction)callable).isStrict();
        } else if (isJSObjectFunction(callable)) {
            return ((JSObject)callable).isStrictFunction();
        } else if (callable instanceof BoundCallable) {
            return isStrictCallable(((BoundCallable)callable).getCallable());
        } else if (BeansLinker.isDynamicMethod(callable) ||
                callable instanceof StaticClass ||
                isFunctionalInterfaceObject(callable)) {
            return false;
        }
        throw notFunction(callable);
    }

    private static ECMAException notFunction(final Object obj) {
        return typeError("not.a.function", ScriptRuntime.safeToString(obj));
    }

    private static boolean isJSObjectFunction(final Object obj) {
        return obj instanceof JSObject && ((JSObject)obj).isFunction();
    }

    /**
     * Returns if the given object is a dynalink Dynamic method
     * @param obj object to be checked
     * @return true if the obj is a dynamic method
     */
    public static boolean isDynamicMethod(final Object obj) {
        return BeansLinker.isDynamicMethod(obj instanceof BoundCallable ? ((BoundCallable)obj).getCallable() : obj);
    }

    /**
     * Returns if the given object is an instance of an interface annotated with
     * java.lang.FunctionalInterface
     * @param obj object to be checked
     * @return true if the obj is an instance of @FunctionalInterface interface
     */
    public static boolean isFunctionalInterfaceObject(final Object obj) {
        return !JSType.isPrimitive(obj) && (NashornBeansLinker.getFunctionalInterfaceMethodName(obj.getClass()) != null);
    }

    /**
     * Create a call site and link it for Nashorn. This version of the method conforms to the invokedynamic bootstrap
     * method expected signature and is referenced from Nashorn generated bytecode as the bootstrap method for all
     * invokedynamic instructions.
     * @param lookup MethodHandle lookup.
     * @param opDesc Dynalink dynamic operation descriptor.
     * @param type   Method type.
     * @param flags  flags for call type, trace/profile etc.
     * @return CallSite with MethodHandle to appropriate method or null if not found.
     */
    public static CallSite bootstrap(final Lookup lookup, final String opDesc, final MethodType type, final int flags) {
        return Context.getDynamicLinker(lookup.lookupClass()).link(LinkerCallSite.newLinkerCallSite(lookup, opDesc, type, flags));
    }

    /**
     * Returns a dynamic invoker for a specified dynamic operation using the
     * public lookup. You can use this method to create a method handle that
     * when invoked acts completely as if it were a Nashorn-linked call site.
     * Note that the available operations are encoded in the flags, see
     * {@link NashornCallSiteDescriptor} operation constants. If the operation
     * takes a name, it should be set otherwise empty name (not null) should be
     * used. All names (including the empty one) should be encoded using
     * {@link NameCodec#encode(String)}. Few examples:
     * <ul>
     *   <li>Get a named property with fixed name:
     *     <pre>
     * MethodHandle getColor = Boostrap.createDynamicInvoker(
     *     "color",
     *     NashornCallSiteDescriptor.GET_PROPERTY,
     *     Object.class, Object.class);
     * Object obj = ...; // somehow obtain the object
     * Object color = getColor.invokeExact(obj);
     *     </pre>
     *   </li>
     *   <li>Get a named property with variable name:
     *     <pre>
     * MethodHandle getProperty = Boostrap.createDynamicInvoker(
     *     NameCodec.encode(""),
     *     NashornCallSiteDescriptor.GET_PROPERTY,
     *     Object.class, Object.class, String.class);
     * Object obj = ...; // somehow obtain the object
     * Object color = getProperty.invokeExact(obj, "color");
     * Object shape = getProperty.invokeExact(obj, "shape");
     *
     * MethodHandle getNumProperty = Boostrap.createDynamicInvoker(
     *     NameCodec.encode(""),
     *     NashornCallSiteDescriptor.GET_ELEMENT,
     *     Object.class, Object.class, int.class);
     * Object elem42 = getNumProperty.invokeExact(obj, 42);
     *     </pre>
     *   </li>
     *   <li>Set a named property with fixed name:
     *     <pre>
     * MethodHandle setColor = Boostrap.createDynamicInvoker(
     *     "color",
     *     NashornCallSiteDescriptor.SET_PROPERTY,
     *     void.class, Object.class, Object.class);
     * Object obj = ...; // somehow obtain the object
     * setColor.invokeExact(obj, Color.BLUE);
     *     </pre>
     *   </li>
     *   <li>Set a property with variable name:
     *     <pre>
     * MethodHandle setProperty = Boostrap.createDynamicInvoker(
     *     NameCodec.encode(""),
     *     NashornCallSiteDescriptor.SET_PROPERTY,
     *     void.class, Object.class, String.class, Object.class);
     * Object obj = ...; // somehow obtain the object
     * setProperty.invokeExact(obj, "color", Color.BLUE);
     * setProperty.invokeExact(obj, "shape", Shape.CIRCLE);
     *     </pre>
     *   </li>
     *   <li>Call a function on an object; note it's a two-step process: get the
     *   method, then invoke the method. This is the actual:
     *     <pre>
     * MethodHandle findFooFunction = Boostrap.createDynamicInvoker(
     *     "foo",
     *     NashornCallSiteDescriptor.GET_METHOD,
     *     Object.class, Object.class);
     * Object obj = ...; // somehow obtain the object
     * Object foo_fn = findFooFunction.invokeExact(obj);
     * MethodHandle callFunctionWithTwoArgs = Boostrap.createDynamicCallInvoker(
     *     Object.class, Object.class, Object.class, Object.class, Object.class);
     * // Note: "call" operation takes a function, then a "this" value, then the arguments:
     * Object foo_retval = callFunctionWithTwoArgs.invokeExact(foo_fn, obj, arg1, arg2);
     *     </pre>
     *   </li>
     * </ul>
     * Few additional remarks:
     * <ul>
     * <li>Just as Nashorn works with any Java object, the invokers returned
     * from this method can also be applied to arbitrary Java objects in
     * addition to Nashorn JavaScript objects.</li>
     * <li>For invoking a named function on an object, you can also use the
     * {@link InvokeByName} convenience class.</li>
     * <li>There's no rule that the variable property identifier has to be a
     * {@code String} for {@code GET_PROPERTY/SET_PROPERTY} and {@code int} for
     * {@code GET_ELEMENT/SET_ELEMENT}. You can declare their type to be
     * {@code int}, {@code double}, {@code Object}, and so on regardless of the
     * kind of the operation.</li>
     * <li>You can be as specific in parameter types as you want. E.g. if you
     * know that the receiver of the operation will always be
     * {@code ScriptObject}, you can pass {@code ScriptObject.class} as its
     * parameter type. If you happen to link to a method that expects different
     * types, (you can use these invokers on POJOs too, after all, and end up
     * linking with their methods that have strongly-typed signatures), all
     * necessary conversions allowed by either Java or JavaScript will be
     * applied: if invoked methods specify either primitive or wrapped Java
     * numeric types, or {@code String} or {@code boolean/Boolean}, then the
     * parameters might be subjected to standard ECMAScript {@code ToNumber},
     * {@code ToString}, and {@code ToBoolean} conversion, respectively. Less
     * obviously, if the expected parameter type is a SAM type, and you pass a
     * JavaScript function, a proxy object implementing the SAM type and
     * delegating to the function will be passed. Linkage can often be optimized
     * when linkers have more specific type information than "everything can be
     * an object".</li>
     * <li>You can also be as specific in return types as you want. For return
     * types any necessary type conversion available in either Java or
     * JavaScript will be automatically applied, similar to the process
     * described for parameters, only in reverse direction: if you specify any
     * either primitive or wrapped Java numeric type, or {@code String} or
     * {@code boolean/Boolean}, then the return values will be subjected to
     * standard ECMAScript {@code ToNumber}, {@code ToString}, and
     * {@code ToBoolean} conversion, respectively. Less obviously, if the return
     * type is a SAM type, and the return value is a JavaScript function, a
     * proxy object implementing the SAM type and delegating to the function
     * will be returned.</li>
     * </ul>
     * @param name name at the call site. Must not be null. Must be encoded
     * using {@link NameCodec#encode(String)}. If the operation does not take a
     * name, use empty string (also has to be encoded).
     * @param flags the call site flags for the operation; see
     * {@link NashornCallSiteDescriptor} for available flags. The most important
     * part of the flags are the ones encoding the actual operation.
     * @param rtype the return type for the operation
     * @param ptypes the parameter types for the operation
     * @return MethodHandle for invoking the operation.
     */
    public static MethodHandle createDynamicInvoker(final String name, final int flags, final Class<?> rtype, final Class<?>... ptypes) {
        return bootstrap(MethodHandles.publicLookup(), name, MethodType.methodType(rtype, ptypes), flags).dynamicInvoker();
    }

    /**
     * Returns a dynamic invoker for the {@link NashornCallSiteDescriptor#CALL}
     * operation using the public lookup.
     * @param rtype the return type for the operation
     * @param ptypes the parameter types for the operation
     * @return a dynamic invoker for the {@code CALL} operation.
     */
    public static MethodHandle createDynamicCallInvoker(final Class<?> rtype, final Class<?>... ptypes) {
        return createDynamicInvoker("", NashornCallSiteDescriptor.CALL, rtype, ptypes);
    }

    /**
     * Returns a dynamic invoker for a specified dynamic operation using the
     * public lookup. Similar to
     * {@link #createDynamicInvoker(String, int, Class, Class...)} but with
     * already precomposed method type.
     * @param name name at the call site.
     * @param flags flags at the call site
     * @param type the method type for the operation
     * @return MethodHandle for invoking the operation.
     */
    public static MethodHandle createDynamicInvoker(final String name, final int flags, final MethodType type) {
        return bootstrap(MethodHandles.publicLookup(), name, type, flags).dynamicInvoker();
    }

    /**
     * Binds any object Nashorn can use as a [[Callable]] to a receiver and optionally arguments.
     * @param callable the callable to bind
     * @param boundThis the bound "this" value.
     * @param boundArgs the bound arguments. Can be either null or empty array to signify no arguments are bound.
     * @return a bound callable.
     * @throws ECMAException with {@code TypeError} if the object is not a callable.
     */
    public static Object bindCallable(final Object callable, final Object boundThis, final Object[] boundArgs) {
        if (callable instanceof ScriptFunction) {
            return ((ScriptFunction)callable).createBound(boundThis, boundArgs);
        } else if (callable instanceof BoundCallable) {
            return ((BoundCallable)callable).bind(boundArgs);
        } else if (isCallable(callable)) {
            return new BoundCallable(callable, boundThis, boundArgs);
        }
        throw notFunction(callable);
    }

    /**
     * Creates a super-adapter for an adapter, that is, an adapter to the adapter that allows invocation of superclass
     * methods on it.
     * @param adapter the original adapter
     * @return a new adapter that can be used to invoke super methods on the original adapter.
     */
    public static Object createSuperAdapter(final Object adapter) {
        return new JavaSuperAdapter(adapter);
    }

    /**
     * If the given class is a reflection-specific class (anything in {@code java.lang.reflect} and
     * {@code java.lang.invoke} package, as well a {@link Class} and any subclass of {@link ClassLoader}) and there is
     * a security manager in the system, then it checks the {@code nashorn.JavaReflection} {@code RuntimePermission}.
     * @param clazz the class being tested
     * @param isStatic is access checked for static members (or instance members)
     */
    public static void checkReflectionAccess(final Class<?> clazz, final boolean isStatic) {
        ReflectionCheckLinker.checkReflectionAccess(clazz, isStatic);
    }

    /**
     * Returns the Nashorn's internally used dynamic linker's services object. Note that in code that is processing a
     * linking request, you will normally use the {@code LinkerServices} object passed by whatever top-level linker
     * invoked the linking (if the call site is in Nashorn-generated code, you'll get this object anyway). You should
     * only resort to retrieving a linker services object using this method when you need some linker services (e.g.
     * type converter method handles) outside of a code path that is linking a call site.
     * @return Nashorn's internal dynamic linker's services object.
     */
    public static LinkerServices getLinkerServices() {
        return Context.getDynamicLinker().getLinkerServices();
    }

    /**
     * Takes a guarded invocation, and ensures its method and guard conform to the type of the call descriptor, using
     * all type conversions allowed by the linker's services. This method is used by Nashorn's linkers as a last step
     * before returning guarded invocations. Most of the code used to produce the guarded invocations does not make an
     * effort to coordinate types of the methods, and so a final type adjustment before a guarded invocation is returned
     * to the aggregating linker is the responsibility of the linkers themselves.
     * @param inv the guarded invocation that needs to be type-converted. Can be null.
     * @param linkerServices the linker services object providing the type conversions.
     * @param desc the call site descriptor to whose method type the invocation needs to conform.
     * @return the type-converted guarded invocation. If input is null, null is returned. If the input invocation
     * already conforms to the requested type, it is returned unchanged.
     */
    static GuardedInvocation asTypeSafeReturn(final GuardedInvocation inv, final LinkerServices linkerServices, final CallSiteDescriptor desc) {
        return inv == null ? null : inv.asTypeSafeReturn(linkerServices, desc.getMethodType());
    }

    /**
     * Adapts the return type of the method handle with {@code explicitCastArguments} when it is an unboxing
     * conversion. This will ensure that nulls are unwrapped to false or 0.
     * @param target the target method handle
     * @param newType the desired new type. Note that this method does not adapt the method handle completely to the
     * new type, it only adapts the return type; this is allowed as per
     * {@link DynamicLinkerFactory#setAutoConversionStrategy(MethodTypeConversionStrategy)}, which is what this method
     * is used for.
     * @return the method handle with adapted return type, if it required an unboxing conversion.
     */
    private static MethodHandle unboxReturnType(final MethodHandle target, final MethodType newType) {
        final MethodType targetType = target.type();
        final Class<?> oldReturnType = targetType.returnType();
        final Class<?> newReturnType = newType.returnType();
        if (TypeUtilities.isWrapperType(oldReturnType)) {
            if (newReturnType.isPrimitive()) {
                // The contract of setAutoConversionStrategy is such that the difference between newType and targetType
                // can only be JLS method invocation conversions.
                assert TypeUtilities.isMethodInvocationConvertible(oldReturnType, newReturnType);
                return MethodHandles.explicitCastArguments(target, targetType.changeReturnType(newReturnType));
            }
        } else if (oldReturnType == void.class && newReturnType == Object.class) {
            return MethodHandles.filterReturnValue(target, VOID_TO_OBJECT);
        }
        return target;
    }

    private static MethodHandle createMissingMemberHandler(
            final LinkRequest linkRequest, final LinkerServices linkerServices) {
        if (BrowserJSObjectLinker.canLinkTypeStatic(linkRequest.getReceiver().getClass())) {
            // Don't create missing member handlers for the browser JS objects as they
            // have their own logic.
            return null;
        }
        return NashornBottomLinker.linkMissingBeanMember(linkRequest, linkerServices);
    }
}
