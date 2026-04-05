package desertreet.blockchat.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class BlockChatKeyMappings {

	private BlockChatKeyMappings() {
	}

	public static Object createCategory(String namespace, String path) {
		return new CategorySpec(namespace, path);
	}

	public static KeyMapping create(String translationKey, InputConstants.Type inputType, int defaultKey, Object category) {
		for (Constructor<?> constructor : KeyMapping.class.getConstructors()) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			try {
				if (parameterTypes.length == 4
					&& parameterTypes[0] == String.class
					&& parameterTypes[1] == InputConstants.Type.class
					&& parameterTypes[2] == int.class) {
					Object compatibleCategory = adaptCategory(category, parameterTypes[3]);
					if (compatibleCategory == null) {
						continue;
					}
					return (KeyMapping) constructor.newInstance(translationKey, inputType, defaultKey, compatibleCategory);
				}
				if (parameterTypes.length == 3
					&& parameterTypes[0] == String.class
					&& parameterTypes[1] == int.class) {
					Object compatibleCategory = adaptCategory(category, parameterTypes[2]);
					if (compatibleCategory == null) {
						continue;
					}
					return (KeyMapping) constructor.newInstance(translationKey, defaultKey, compatibleCategory);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Could not create a compatible KeyMapping instance", e);
			}
		}
		throw new IllegalStateException("Could not find a compatible KeyMapping constructor");
	}

	public static KeyMapping register(KeyMapping mapping) {
		if (mapping == null) {
			return null;
		}
		KeyMapping registered = invoke(
			"net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper",
			"registerKeyBinding",
			mapping
		);
		if (registered != null) {
			return registered;
		}
		registered = invoke(
			"net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper",
			"registerKeyMapping",
			mapping
		);
		if (registered != null) {
			return registered;
		}
		throw new IllegalStateException("Could not find a Fabric key mapping registration helper");
	}

	private static KeyMapping invoke(String className, String methodName, KeyMapping mapping) {
		try {
			Class<?> helperClass = Class.forName(className);
			Method method = helperClass.getMethod(methodName, KeyMapping.class);
			Object result = method.invoke(null, mapping);
			return result instanceof KeyMapping km ? km : mapping;
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private static Object adaptCategory(Object category, Class<?> expectedType) {
		if (category == null) {
			return null;
		}
		if (expectedType.isInstance(category)) {
			return category;
		}
		if (expectedType == String.class) {
			if (category instanceof CategorySpec spec) {
				return spec.translationKey();
			}
			return String.valueOf(category);
		}
		if (category instanceof CategorySpec spec) {
			return instantiateCategory(expectedType, spec);
		}
		return null;
	}

	private static Object instantiateCategory(Class<?> categoryType, CategorySpec spec) {
		for (Method method : categoryType.getMethods()) {
			if (!Modifier.isStatic(method.getModifiers()) || !categoryType.isAssignableFrom(method.getReturnType())) {
				continue;
			}
			Object argument = buildArgument(method, spec);
			if (argument == null) {
				continue;
			}
			try {
				return method.invoke(null, argument);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		for (Constructor<?> constructor : categoryType.getConstructors()) {
			Object argument = buildArgument(constructor, spec);
			if (argument == null) {
				continue;
			}
			try {
				return constructor.newInstance(argument);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		for (Method method : categoryType.getDeclaredMethods()) {
			if (!Modifier.isStatic(method.getModifiers()) || !categoryType.isAssignableFrom(method.getReturnType())) {
				continue;
			}
			Object argument = buildArgument(method, spec);
			if (argument == null) {
				continue;
			}
			try {
				method.setAccessible(true);
				return method.invoke(null, argument);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		for (Constructor<?> constructor : categoryType.getDeclaredConstructors()) {
			Object argument = buildArgument(constructor, spec);
			if (argument == null) {
				continue;
			}
			try {
				constructor.setAccessible(true);
				return constructor.newInstance(argument);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return null;
	}

	private static Object buildArgument(Executable executable, CategorySpec spec) {
		Class<?>[] parameterTypes = executable.getParameterTypes();
		if (parameterTypes.length != 1) {
			return null;
		}
		Class<?> parameterType = parameterTypes[0];
		if (parameterType == String.class) {
			return spec.translationKey();
		}
		return createNamespacedValue(parameterType, spec.namespace(), spec.path());
	}

	private static Object createNamespacedValue(Class<?> valueType, String namespace, String path) {
		for (Method method : valueType.getMethods()) {
			if (!Modifier.isStatic(method.getModifiers()) || !valueType.isAssignableFrom(method.getReturnType())) {
				continue;
			}
			Class<?>[] parameterTypes = method.getParameterTypes();
			try {
				if (parameterTypes.length == 2
					&& parameterTypes[0] == String.class
					&& parameterTypes[1] == String.class) {
					return method.invoke(null, namespace, path);
				}
				if (parameterTypes.length == 1 && parameterTypes[0] == String.class) {
					return method.invoke(null, namespace + ":" + path);
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		for (Constructor<?> constructor : valueType.getConstructors()) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			try {
				if (parameterTypes.length == 2
					&& parameterTypes[0] == String.class
					&& parameterTypes[1] == String.class) {
					return constructor.newInstance(namespace, path);
				}
				if (parameterTypes.length == 1 && parameterTypes[0] == String.class) {
					return constructor.newInstance(namespace + ":" + path);
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return null;
	}

	private record CategorySpec(String namespace, String path) {
		private String translationKey() {
			return "key.category." + namespace + "." + path;
		}
	}
}
