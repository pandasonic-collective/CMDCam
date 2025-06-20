package team.creative.cmdcam.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import team.creative.cmdcam.CMDCam;
import team.creative.cmdcam.common.scene.CamScene;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class CamSaveData extends SavedData {
    
    public static final String DATA_NAME = CMDCam.MODID + "_Scenes";
    
    private HashMap<String, CamScene> scenes = new HashMap<>();
    
    // Codec for serialization
    public static final Codec<CamSaveData> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Codec.unboundedMap(Codec.STRING, CamScene.CODEC)
                .fieldOf("scenes").forGetter(data -> data.scenes)
        ).apply(instance, map -> {
            CamSaveData data = new CamSaveData();
            data.scenes.putAll(map);
            return data;
        })
    );
    
    public static final SavedDataType<CamSaveData> ID = new SavedDataType<>(
        DATA_NAME,
        CamSaveData::new,
        CODEC
    );
    
    public CamSaveData() {}
    
    public CamSaveData(CompoundTag nbt, HolderLookup.Provider provider) {
        for (String key : nbt.keySet()) {
            try {
                scenes.put(key, new CamScene(nbt.getCompound(key).orElse(new CompoundTag())));
            } catch (Exception e) {
                // Log or ignore invalid scenes
                e.printStackTrace();
            }
        }
    }
    
    public CamScene get(String key) {
        return scenes.get(key);
    }
    
    public void set(String key, CamScene path) {
        scenes.put(key, path);
        setDirty();
    }
    
    public boolean remove(String key) {
        return scenes.remove(key) != null;
    }
    
    public Collection<String> names() {
        return scenes.keySet();
    }
    
    public void clear() {
        scenes.clear();
        setDirty();
    }
    
    public CompoundTag save(CompoundTag nbt, Provider provider) {
        for (Entry<String, CamScene> entry : scenes.entrySet())
            nbt.put(entry.getKey(), entry.getValue().save(new CompoundTag()));
        return nbt;
    }
    
}
