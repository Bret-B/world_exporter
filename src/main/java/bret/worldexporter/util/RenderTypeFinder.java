package bret.worldexporter.util;

import net.minecraft.client.renderer.RenderType;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

// Some mods extend RenderType and use setupState() and clearState() to call a nested setupState/clearState for
// a RenderType.Type as a field for that extended RenderType.

// It appears that all texture binding for purposes of rendering blocks/entities/etc. happens in
// RenderType.Type's setupState(), so the RenderType.Type needs to be found to access its RenderType.State
// which contains a reference to a RenderState.textureState

// This will fail if the provided type has no reference to a RenderType.Type.
// This could occur if the extended RenderType manually chooses to bind textures in setupState()
// I have no idea what could be done other than running setupState() and looking for calls to TextureManager.bind()
// any calls to setupState() would need to be done on the render/main thread

// This recursively walks the fields of a RenderType class to try and find an initialized RenderType.Type field
// The fields are walked in order given by Java's reflection, and the first matching RenderType.Type is returned
public class RenderTypeFinder {
    @Nullable
    public static RenderType.Type findNestedType(Object object, int maxDepth) {
        if (object instanceof RenderType.Type) {
            return (RenderType.Type) object;
        }
        if (maxDepth <= 0 || object == null) return null;

        Field[] fields = ReflectionHandler.getDeclaredFields(object);
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                Object fieldObject = field.get(object);
                RenderType.Type fieldResult = findNestedType(fieldObject, maxDepth - 1);
                if (fieldResult != null) {
                    return fieldResult;
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }
}
