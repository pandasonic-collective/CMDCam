package team.creative.cmdcam;

import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import team.creative.cmdcam.client.CMDCamClient;
import team.creative.cmdcam.client.CamEventHandlerClient;
import team.creative.cmdcam.common.command.argument.CamModeArgument;
import team.creative.cmdcam.common.command.argument.CamPitchModeArgument;
import team.creative.cmdcam.common.command.argument.DurationArgument;
import team.creative.cmdcam.common.command.argument.InterpolationArgument;
import team.creative.cmdcam.common.command.argument.InterpolationArgument.AllInterpolationArgument;
import team.creative.cmdcam.common.command.builder.PointArgumentBuilder;
import team.creative.cmdcam.common.command.builder.SceneCommandBuilder;
import team.creative.cmdcam.common.command.builder.SceneStartCommandBuilder;
import team.creative.cmdcam.common.math.interpolation.CamInterpolation;
import team.creative.cmdcam.common.math.point.CamPoint;
import team.creative.cmdcam.common.packet.ConnectPacket;
import team.creative.cmdcam.common.packet.GetPathPacket;
import team.creative.cmdcam.common.packet.PausePathPacket;
import team.creative.cmdcam.common.packet.ResumePathPacket;
import team.creative.cmdcam.common.packet.SetPathPacket;
import team.creative.cmdcam.common.packet.StartPathPacket;
import team.creative.cmdcam.common.packet.StopPathPacket;
import team.creative.cmdcam.common.packet.TeleportPathPacket;
import team.creative.cmdcam.common.scene.CamScene;
import team.creative.cmdcam.server.CMDCamServer;
import team.creative.cmdcam.server.CamEventHandler;
import team.creative.creativecore.common.config.holder.CreativeConfigRegistry;
import team.creative.creativecore.common.network.CreativeNetwork;
import team.creative.creativecore.common.network.CreativePacket;

@Mod(value = CMDCam.MODID)
public class CMDCam {
    
    public static final String MODID = "cmdcam";
    
    private static final Logger LOGGER = LogManager.getLogger(CMDCam.MODID);
    public static final CreativeNetwork NETWORK = new CreativeNetwork(1, LOGGER, ResourceLocation.tryBuild(CMDCam.MODID, "main"));
    public static final CMDCamConfig CONFIG = new CMDCamConfig();
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES = DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, MODID);
    
    public CMDCam(IEventBus bus) {
        bus.addListener(this::init);
        if (FMLLoader.getDist() == Dist.CLIENT)
            CMDCamClient.load(bus);
        NeoForge.EVENT_BUS.addListener(this::commands);
        
        COMMAND_ARGUMENT_TYPES.register(bus);
        COMMAND_ARGUMENT_TYPES.register("duration", () -> ArgumentTypeInfos.registerByClass(DurationArgument.class, SingletonArgumentInfo.<DurationArgument>contextFree(
            () -> DurationArgument.duration())));
        COMMAND_ARGUMENT_TYPES.register("cam_mode", () -> ArgumentTypeInfos.registerByClass(CamModeArgument.class, SingletonArgumentInfo.<CamModeArgument>contextFree(
            () -> CamModeArgument.mode())));
        COMMAND_ARGUMENT_TYPES.register("interpolation", () -> ArgumentTypeInfos.registerByClass(InterpolationArgument.class, SingletonArgumentInfo
                .<InterpolationArgument>contextFree(() -> InterpolationArgument.interpolation())));
        COMMAND_ARGUMENT_TYPES.register("all_interpolation", () -> ArgumentTypeInfos.registerByClass(AllInterpolationArgument.class, SingletonArgumentInfo
                .<AllInterpolationArgument>contextFree(() -> InterpolationArgument.interpolationAll())));
        COMMAND_ARGUMENT_TYPES.register("pitch_mode", () -> ArgumentTypeInfos.registerByClass(CamPitchModeArgument.class, SingletonArgumentInfo.<CamPitchModeArgument>contextFree(
            () -> CamPitchModeArgument.pitchMode())));
    }
    
    private void init(final FMLCommonSetupEvent event) {
        NETWORK.registerType(ConnectPacket.class, ConnectPacket::new);
        NETWORK.registerType(GetPathPacket.class, GetPathPacket::new);
        NETWORK.registerType(SetPathPacket.class, SetPathPacket::new);
        NETWORK.registerType(StartPathPacket.class, StartPathPacket::new);
        NETWORK.registerType(StopPathPacket.class, StopPathPacket::new);
        NETWORK.registerType(TeleportPathPacket.class, TeleportPathPacket::new);
        NETWORK.registerType(PausePathPacket.class, PausePathPacket::new);
        NETWORK.registerType(ResumePathPacket.class, ResumePathPacket::new);
        
        NeoForge.EVENT_BUS.register(new CamEventHandler());
        
        CreativeConfigRegistry.ROOT.registerValue(MODID, CONFIG);
    }
    
    private void commands(final RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> camServer = Commands.literal("cam-server");
        addServerCommandsToServer(camServer, event);
        LiteralArgumentBuilder<CommandSourceStack> cam = Commands.literal("cam");
        addClientCommandsToServer(cam, event);
    }

    private void addServerCommandsToServer(LiteralArgumentBuilder<CommandSourceStack> camServer, RegisterCommandsEvent event) {
        SceneStartCommandBuilder.start(camServer, CMDCamServer.PROCESSOR);

        LiteralArgumentBuilder<CommandSourceStack> get = Commands.literal("get");
        SceneCommandBuilder.scene(get, CMDCamServer.PROCESSOR);
        camServer.then(get);

        event.getDispatcher().register(camServer.then(Commands.literal("stop").then(Commands.argument("players", EntityArgument.players()).executes(x -> {
            CreativePacket packet = new StopPathPacket();
            for (ServerPlayer player : EntityArgument.getPlayers(x, "players"))
                CMDCam.NETWORK.sendToClient(packet, player);
            return 0;
        }))).then(Commands.literal("pause").then(Commands.argument("players", EntityArgument.players()).executes(x -> {
            CreativePacket packet = new PausePathPacket();
            for (ServerPlayer player : EntityArgument.getPlayers(x, "players"))
                CMDCam.NETWORK.sendToClient(packet, player);
            return 0;
        }))).then(Commands.literal("resume").then(Commands.argument("players", EntityArgument.players()).executes(x -> {
            CreativePacket packet = new ResumePathPacket();
            for (ServerPlayer player : EntityArgument.getPlayers(x, "players"))
                CMDCam.NETWORK.sendToClient(packet, player);
            return 0;
        }))).then(Commands.literal("list").executes((x) -> {
            Collection<String> names = CMDCamServer.getSavedPaths(x.getSource().getLevel());
            x.getSource().sendSystemMessage(Component.translatable("scenes.list", names.size(), String.join(", ", names)));
            return 0;
        })).then(Commands.literal("clear").executes((x) -> {
            CMDCamServer.clearPaths(x.getSource().getLevel());
            x.getSource().sendSuccess(() -> Component.translatable("scenes.clear"), true);
            return 0;
        })).then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.string()).executes((x) -> {
            String name = StringArgumentType.getString(x, "name");
            if (CMDCamServer.removePath(x.getSource().getLevel(), name))
                x.getSource().sendSuccess(() -> Component.translatable("scene.remove", name), true);
            else
                x.getSource().sendFailure(Component.translatable("scene.remove_fail", name));
            return 0;
        }))).then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.string()).executes((x) -> {
            String name = StringArgumentType.getString(x, "name");
            if (CMDCamServer.get(x.getSource().getLevel(), name) != null)
                x.getSource().sendSuccess(() -> Component.translatable("scene.exists", name), true);
            else {
                CMDCamServer.set(x.getSource().getLevel(), name, CamScene.createDefault());
                x.getSource().sendSuccess(() -> Component.translatable("scene.create", name), true);
            }
            return 0;
        }))));
    }
    private void addClientCommandsToServer(LiteralArgumentBuilder<CommandSourceStack> cam, RegisterCommandsEvent event) {
        SceneStartCommandBuilder.start(cam, CMDCamServer.PROCESSOR);
        SceneCommandBuilder.scene(cam, CMDCamServer.PROCESSOR);

        cam.then(Commands.literal("stop").executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new StopPathPacket(), x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }));

        cam.then(Commands.literal("pause").executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new PausePathPacket(), x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }));

        cam.then(Commands.literal("resume").executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new ResumePathPacket(), x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }));

        cam.then(Commands.literal("show").executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        CamEventHandlerClient.SHOW_ACTIVE_INTERPOLATION = true;
                        player.displayClientMessage(Component.translatable("scene.interpolation.show_active"), false);
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }).then(Commands.argument("interpolation", InterpolationArgument.interpolationAll()).executes(x -> {
            String interpolation = StringArgumentType.getString(x, "interpolation");
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        if (!interpolation.equalsIgnoreCase("all")) {
                            CamInterpolation.REGISTRY.get(interpolation).isRenderingEnabled = true;
                            player.displayClientMessage(Component.translatable("scene.interpolation.show", interpolation), false);
                        } else {
                            for (CamInterpolation movement : CamInterpolation.REGISTRY.values())
                                movement.isRenderingEnabled = true;
                            player.displayClientMessage(Component.translatable("scene.interpolation.show_all"), false);
                        }
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        })));

        cam.then(Commands.literal("hide").executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        CamEventHandlerClient.SHOW_ACTIVE_INTERPOLATION = false;
                        player.displayClientMessage(Component.translatable("scene.interpolation.hide_active"), false);
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }).then(Commands.argument("interpolation", InterpolationArgument.interpolationAll()).executes(x -> {
            String interpolation = StringArgumentType.getString(x, "interpolation");
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        if (!interpolation.equalsIgnoreCase("all")) {
                            CamInterpolation.REGISTRY.get(interpolation).isRenderingEnabled = false;
                            player.displayClientMessage(Component.translatable("scene.interpolation.hide", interpolation), false);
                        } else {
                            for (CamInterpolation movement : CamInterpolation.REGISTRY.values())
                                movement.isRenderingEnabled = false;
                            CamEventHandlerClient.SHOW_ACTIVE_INTERPOLATION = false;
                            player.displayClientMessage(Component.translatable("scene.interpolation.hide_all"), false);
                        }
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        })));

        cam.then(Commands.literal("list").executes(x -> {
            Collection<String> names = CMDCamServer.getSavedPaths(x.getSource().getLevel());
            x.getSource().sendSystemMessage(Component.translatable("scenes.list", names.size(), String.join(", ", names)));
            return 0;
        }));

        cam.then(Commands.literal("load").then(Commands.argument("path", StringArgumentType.string()).executes(x -> {
            String pathArg = StringArgumentType.getString(x, "path");
            try {
                CMDCam.NETWORK.sendToClient(new GetPathPacket(pathArg), x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        })));

        cam.then(Commands.literal("save").then(Commands.argument("path", StringArgumentType.string()).executes(x -> {
            String pathArg = StringArgumentType.getString(x, "path");
            CamScene scene = CMDCamServer.get(x.getSource().getLevel(), pathArg);
            if (scene == null) {
                scene = CamScene.createDefault();
            }
            CMDCamServer.set(x.getSource().getLevel(), pathArg, scene);
            x.getSource().sendSuccess(() -> Component.translatable("scenes.save", pathArg), false);
            return 0;
        })));

        cam.then(new PointArgumentBuilder("follow_center", (x, y) -> {
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        CMDCamClient.setTargetMarker(y);
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
        }, CMDCamServer.PROCESSOR).executes(x -> {
            try {
                CMDCam.NETWORK.sendToClient(new CreativePacket() {
                    @Override
                    public void executeClient(Player player) {
                        CMDCamClient.setTargetMarker(CamPoint.createLocal());
                    }
                    
                    @Override
                    public void executeServer(ServerPlayer player) {}
                }, x.getSource().getPlayerOrException());
            } catch (CommandSyntaxException e) {
                x.getSource().sendFailure(Component.literal("Player not found"));
            }
            return 0;
        }));
    }
}
