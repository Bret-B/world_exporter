package bret.worldexporter.util;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectionHandler {
    @Nullable
    public static MethodHandle getMethod(Object className, String methodName, Object... methodParameterClassNames) {
        try {
            Class<?> classReference = className instanceof Class<?> ? (Class<?>) className : Class.forName((String) className);
            Class<?>[] parameters = new Class[methodParameterClassNames.length];
            for (int i = 0; i < methodParameterClassNames.length; ++i) {
                Object param = methodParameterClassNames[i];
                parameters[i] = param instanceof Class<?> ? (Class<?>) param : Class.forName((String) param);
            }
            Method method = classReference.getDeclaredMethod(methodName, parameters);
            method.setAccessible(true);
            return MethodHandles.publicLookup().unreflect(method);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static Field getField(Object className, String fieldName) {
        try {
            Class<?> classReference = className instanceof Class<?> ? (Class<?>) className : Class.forName((String) className);
            Field field = classReference.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            return null;
        }
    }

    public static Field[] getDeclaredFields(Object object) {
        return object.getClass().getDeclaredFields();
    }
}
