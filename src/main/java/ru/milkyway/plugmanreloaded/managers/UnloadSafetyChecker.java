package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class UnloadSafetyChecker {

    public enum PluginRiskLevel {
        SAFE,
        API_PROVIDER,
        LOW_LEVEL_NETWORK,
        HAS_DEPENDENTS,
        UNLOADABLE_HOSTILE,
        CRITICAL_PROTECTED
    }

    private static final String[] HOSTILE_MARKER_CLASSES = {
            "com.comphenix.protocol.ProtocolLibrary",
            "com.viaversion.viaversion.ViaVersionPlugin",
            "us.myles.ViaVersion.ViaVersionPlugin",
            "com.viaversion.viabackwards.BukkitPlugin",
            "com.viaversion.viabackwards.ViaBackwards",
            "com.viaversion.viarewind.BukkitPlugin",
            "com.viaversion.viarewind.ViaRewind",
            "protocolsupport.ProtocolSupport",
            "org.geysermc.geyser.platform.spigot.GeyserSpigotPlugin",
            "org.geysermc.floodgate.FloodgatePlugin"
    };

    public record SafetyAssessment(
            PluginRiskLevel riskLevel,
            Set<String> dependents
    ) {
        public SafetyAssessment {
            if (dependents == null) dependents = Collections.emptySet();
        }
    }

    private final PlugManReloaded plugin;

    public UnloadSafetyChecker(PlugManReloaded plugin) {
        this.plugin = plugin;
    }

    public SafetyAssessment assess(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null) {
            return new SafetyAssessment(PluginRiskLevel.SAFE, Collections.emptySet());
        }

        if (targetPlugin.equals(plugin) || targetPlugin.getName().equalsIgnoreCase(plugin.getName())) {
            return new SafetyAssessment(PluginRiskLevel.CRITICAL_PROTECTED, Collections.emptySet());
        }

        Set<String> dependents = plugin.getPluginLifecycleManager().getDependencyGraph().getDependents(targetPlugin.getName(), true);

        if (isKnownHostile(targetPlugin)) {
            return new SafetyAssessment(PluginRiskLevel.UNLOADABLE_HOSTILE, dependents);
        }

        File pluginFile = plugin.getPluginLifecycleManager().getPluginFile(targetPlugin);
        if (pluginFile != null && JarValidator.hasPaperBootstrapper(pluginFile)) {
            return new SafetyAssessment(PluginRiskLevel.UNLOADABLE_HOSTILE, dependents);
        }

        if (NettyGuard.hasInjectedHandlers(targetPlugin)) {
            return new SafetyAssessment(PluginRiskLevel.LOW_LEVEL_NETWORK, dependents);
        }

        Set<Class<?>> providedServices = getProvidedServices(targetPlugin);
        if (!providedServices.isEmpty()) {
            return new SafetyAssessment(PluginRiskLevel.API_PROVIDER, dependents);
        }

        if (!dependents.isEmpty()) {
            return new SafetyAssessment(PluginRiskLevel.HAS_DEPENDENTS, dependents);
        }

        return new SafetyAssessment(PluginRiskLevel.SAFE, Collections.emptySet());
    }

    private boolean isKnownHostile(Plugin targetPlugin) {
        if (plugin.getConfigManager().isUnsafeToUnload(targetPlugin.getName())) {
            return true;
        }

        ClassLoader loader = targetPlugin.getClass().getClassLoader();
        if (loader == null) return false;

        for (String marker : HOSTILE_MARKER_CLASSES) {
            try {
                Class<?> found = Class.forName(marker, false, loader);
                if (found.getClassLoader() == loader) {
                    return true;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError expected) {
                continue;
            } catch (Throwable t) {
                Log.debug("unloadsafetychecker.marker-check-failed", t, "marker", marker, "plugin", targetPlugin.getName());
            }
        }
        return false;
    }

    private Set<Class<?>> getProvidedServices(Plugin targetPlugin) {
        Set<Class<?>> services = new HashSet<>();
        try {
            for (Class<?> serviceClass : Bukkit.getServicesManager().getKnownServices()) {
                for (RegisteredServiceProvider<?> provider : Bukkit.getServicesManager().getRegistrations(serviceClass)) {
                    if (targetPlugin.equals(provider.getPlugin())) {
                        services.add(serviceClass);
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("unloadsafetychecker.servicesmanager-scan-failed", t, "plugin", targetPlugin.getName());
        }
        return services;
    }
}

