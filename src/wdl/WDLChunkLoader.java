package wdl;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBeacon;
import net.minecraft.block.BlockBrewingStand;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDropper;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockNote;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ClassInheratanceMultiMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.SaveHandler;

/**
 * Alternative implementation of {@link AnvilChunkLoader} that handles editing
 * WDL-specific properties of chunks as they are being saved.
 */
public class WDLChunkLoader extends AnvilChunkLoader {

	public static WDLChunkLoader create(SaveHandler handler,
			WorldProvider provider) {
		return new WDLChunkLoader(getWorldSaveFolder(handler, provider));
	}

	/**
	 * Gets the save folder for the given WorldProvider, respecting Forge's
	 * dimension names if forge is present.
	 */
	private static File getWorldSaveFolder(SaveHandler handler,
			WorldProvider provider) {
		File baseFolder = handler.getWorldDirectory();

		// Based off of AnvilSaveHandler.getChunkLoader, but also accounts
		// for forge changes.
		try {
			// See forge changes here:
			// https://github.com/MinecraftForge/MinecraftForge/blob/250a77b35936e7ac68006dfd28a9e93c6def9128/patches/minecraft/net/minecraft/world/WorldProvider.java.patch#L85-L93
			// https://github.com/MinecraftForge/MinecraftForge/blob/250a77b35936e7ac68006dfd28a9e93c6def9128/patches/minecraft/net/minecraft/world/chunk/storage/AnvilSaveHandler.java.patch
			Method forgeGetSaveFolderMethod = provider.getClass().getMethod(
					"getSaveFolder");

			String name = (String) forgeGetSaveFolderMethod.invoke(provider);
			if (name != null) {
				File file = new File(baseFolder, name);
				file.mkdirs();
				return file;
			}
			return baseFolder;
		} catch (Exception e) {
			// Not a forge setup - emulate the vanilla method in
			// AnvilSaveHandler.getChunkLoader.

			if (provider instanceof WorldProviderHell) {
				File file = new File(baseFolder, "DIM-1");
				file.mkdirs();
				return file;
			} else if (provider instanceof WorldProviderEnd) {
				File file = new File(baseFolder, "DIM1");
				file.mkdirs();
				return file;
			}

			return baseFolder;
		}
	}

	private final File chunkSaveLocation;

	public WDLChunkLoader(File file) {
		super(file);
		this.chunkSaveLocation = file;
	}

	/**
	 * Saves the given chunk.
	 * 
	 * Note that while the normal implementation swallows Exceptions, this
	 * version does not.
	 */
	@Override
	public void saveChunk(World world, Chunk chunk) throws MinecraftException,
			IOException {
		world.checkSessionLock();

		NBTTagCompound levelTag = writeChunkToNBT(chunk, world);

		NBTTagCompound rootTag = new NBTTagCompound();
		rootTag.setTag("Level", levelTag);

		addChunkToPending(chunk.getChunkCoordIntPair(), rootTag);
	}

	/**
	 * Writes the given chunk, creating an NBT compound tag.
	 * 
	 * Note that this does <b>not</b> override the private method
	 * {@link AnvilChunkLoader#writeChunkToNBT(Chunk, World, NBTCompoundTag)}.
	 * That method is private and cannot be overridden; plus, this version
	 * returns a tag rather than modifying the one passed as an argument.
	 * However, that method
	 * 
	 * @param chunk
	 *            The chunk to write
	 * @param world
	 *            The world the chunk is in, used to determine the modified
	 *            time.
	 * @return A new NBTTagCompound
	 */
	@SuppressWarnings("unchecked")
	private NBTTagCompound writeChunkToNBT(Chunk chunk, World world) {
		NBTTagCompound compound = new NBTTagCompound();

		compound.setByte("V", (byte) 1);
		compound.setInteger("xPos", chunk.xPosition);
		compound.setInteger("zPos", chunk.zPosition);
		compound.setLong("LastUpdate", world.getTotalWorldTime());
		compound.setIntArray("HeightMap", chunk.getHeightMap());
		compound.setBoolean("TerrainPopulated", chunk.isTerrainPopulated());
		compound.setBoolean("LightPopulated", chunk.isLightPopulated());
		compound.setLong("InhabitedTime", chunk.getInhabitedTime());
		ExtendedBlockStorage[] blockStorageArray = chunk.getBlockStorageArray();
		NBTTagList blockStorageList = new NBTTagList();
		boolean hasNoSky = !world.provider.getHasNoSky();

		for (ExtendedBlockStorage blockStorage : blockStorageArray) {
			if (blockStorage != null) {
				NBTTagCompound blockData = new NBTTagCompound();
				blockData.setByte("Y",
						(byte) (blockStorage.getYLocation() >> 4 & 255));
				byte[] var12 = new byte[blockStorage.getData().length];
				NibbleArray var13 = new NibbleArray();
				NibbleArray var14 = null;

				for (int var15 = 0; var15 < blockStorage.getData().length; ++var15) {
					char var16 = blockStorage.getData()[var15];
					int var17 = var15 & 15;
					int var18 = var15 >> 8 & 15;
					int var19 = var15 >> 4 & 15;

					if (var16 >> 12 != 0) {
						if (var14 == null) {
							var14 = new NibbleArray();
						}

						var14.set(var17, var18, var19, var16 >> 12);
					}

					var12[var15] = (byte) (var16 >> 4 & 255);
					var13.set(var17, var18, var19, var16 & 15);
				}

				blockData.setByteArray("Blocks", var12);
				blockData.setByteArray("Data", var13.getData());

				if (var14 != null) {
					blockData.setByteArray("Add", var14.getData());
				}

				blockData.setByteArray("BlockLight", blockStorage
						.getBlocklightArray().getData());

				if (hasNoSky) {
					blockData.setByteArray("SkyLight", blockStorage
							.getSkylightArray().getData());
				} else {
					blockData.setByteArray("SkyLight", new byte[blockStorage
							.getBlocklightArray().getData().length]);
				}

				blockStorageList.appendTag(blockData);
			}
		}

		compound.setTag("Sections", blockStorageList);
		compound.setByteArray("Biomes", chunk.getBiomeArray());
		chunk.setHasEntities(false);
		NBTTagList entityList = new NBTTagList();

		for (ClassInheratanceMultiMap map : chunk.getEntityLists()) {
			for (Entity entity : (Iterable<Entity>) map) {
				NBTTagCompound entityData = new NBTTagCompound();

				if (entity.writeToNBTOptional(entityData)) {
					chunk.setHasEntities(true);
					entityList.appendTag(entityData);
				}
			}
		}

		compound.setTag("Entities", entityList);

		NBTTagList tileEntityList = saveTileEntities(chunk);
		compound.setTag("TileEntities", tileEntityList);
		List<NextTickListEntry> updateList = world.getPendingBlockUpdates(
				chunk, false);

		if (updateList != null) {
			long worldTime = world.getTotalWorldTime();
			NBTTagList entries = new NBTTagList();

			for (NextTickListEntry entry : updateList) {
				NBTTagCompound entryTag = new NBTTagCompound();
				ResourceLocation location = (ResourceLocation) Block.blockRegistry
						.getNameForObject(entry.func_151351_a());
				entryTag.setString("i",
						location == null ? "" : location.toString());
				entryTag.setInteger("x", entry.field_180282_a.getX());
				entryTag.setInteger("y", entry.field_180282_a.getY());
				entryTag.setInteger("z", entry.field_180282_a.getZ());
				entryTag.setInteger("t",
						(int) (entry.scheduledTime - worldTime));
				entryTag.setInteger("p", entry.priority);
				entries.appendTag(entryTag);
			}

			compound.setTag("TileTicks", entries);
		}

		return compound;
	}

	/**
	 * Creates an NBT list of all tile entities in this chunk, importing tile
	 * entities as needed.
	 */
	public NBTTagList saveTileEntities(Chunk chunk) {
		NBTTagList tileEntityList = new NBTTagList();
		
		if (!WDLPluginChannels.canSaveTileEntities(chunk)) {
			return tileEntityList;
		}
		
		@SuppressWarnings("unchecked")
		Map<BlockPos, TileEntity> tileEntityMap = chunk.getTileEntityMap();

		// The benefit of a map is that it allows us to overwrite old values.
		Map<BlockPos, NBTTagCompound> dataMap = new HashMap<BlockPos, NBTTagCompound>();

		for (Map.Entry<BlockPos, TileEntity> e : tileEntityMap.entrySet()) {
			TileEntity te = e.getValue();
			NBTTagCompound teData = new NBTTagCompound();
			te.writeToNBT(teData);
			dataMap.put(e.getKey(), teData);
		}
		// TODO: I may be redundantly writing a tile entity out, but it seem
		// easier to do that than check if it's imported.
		dataMap.putAll(importTileEntities(chunk));
		
		for (NBTTagCompound compound : dataMap.values()) {
			tileEntityList.appendTag(compound);
		}
		
		return tileEntityList;
	}

	/**
	 * Gets a map of all tile entities in the previous version of that chunk.
	 */
	public Map<BlockPos, NBTTagCompound> importTileEntities(Chunk chunk) {
		DataInputStream dis = null;
		Map<BlockPos, NBTTagCompound> returned = new HashMap<BlockPos, NBTTagCompound>();

		try {
			dis = RegionFileCache.getChunkInputStream(chunkSaveLocation,
					chunk.xPosition, chunk.zPosition);

			if (dis == null) {
				// This happens whenever the chunk hasn't been saved before.
				// It's a normal case.
				return returned;
			}

			NBTTagCompound chunkNBT = CompressedStreamTools.read(dis);
			NBTTagCompound levelNBT = chunkNBT.getCompoundTag("Level");
			NBTTagList oldList = levelNBT.getTagList("TileEntities", 10);

			if (oldList != null) {
				for (int i = 0; i < oldList.tagCount(); i++) {
					NBTTagCompound oldNBT = oldList.getCompoundTagAt(i);
					String entityID = oldNBT.getString("id");
					BlockPos pos = new BlockPos(oldNBT.getInteger("x"),
							oldNBT.getInteger("y"), oldNBT.getInteger("z"));

					if (shouldImportTileEntity(entityID, pos)) {
						if (!WDL.newTileEntities.containsKey(pos)) {
							// The player didn't save this tile entity in
							// this download session. So we use the old one.
							// Note that this doesn't mean that the old one's
							// a valid one; it could be empty.
							WDLMessages.chatMessageTranslated(
									WDLMessageTypes.LOAD_TILE_ENTITY,
									"wdl.messages.tileEntity.usingOld",
									entityID, pos);
							returned.put(pos, oldNBT);
						} else {
							NBTTagCompound compound = new NBTTagCompound();
							WDL.newTileEntities.get(pos).writeToNBT(compound);
							WDLMessages.chatMessageTranslated(
									WDLMessageTypes.LOAD_TILE_ENTITY,
									"wdl.messages.tileEntity.usingNew",
									entityID, pos);
							returned.put(pos, compound);
						}
					} else {
						WDLMessages.chatMessageTranslated(
								WDLMessageTypes.LOAD_TILE_ENTITY,
								"wdl.messages.tileEntity.notImporting",
								entityID, pos);
					}
				}
			}
		} catch (Exception e) {
			WDLMessages.chatMessageTranslated(WDLMessageTypes.ERROR,
					"wdl.messages.generalError.failedToImportTE",
					chunk.xPosition, chunk.zPosition, e);
		} finally {
			if (dis != null) {
				try {
					dis.close();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}
		return returned;
	}

	/**
	 * Checks if the TileEntity should be imported. Only "problematic" (IE,
	 * those that require manual interaction such as chests) TileEntities will
	 * be imported.
	 */
	public boolean shouldImportTileEntity(String entityID, BlockPos pos) {
		Block block = WDL.worldClient.getBlockState(pos).getBlock();

		if (block instanceof BlockChest && entityID.equals("Chest")) {
			return true;
		} else if (block instanceof BlockDispenser && entityID.equals("Trap")) {
			return true;
		} else if (block instanceof BlockDropper && entityID.equals("Dropper")) {
			return true;
		} else if (block instanceof BlockFurnace && entityID.equals("Furnace")) {
			return true;
		} else if (block instanceof BlockNote && entityID.equals("Music")) {
			return true;
		} else if (block instanceof BlockBrewingStand
				&& entityID.equals("Cauldron")) {
			return true;
		} else if (block instanceof BlockHopper && entityID.equals("Hopper")) {
			return true;
		} else if (block instanceof BlockBeacon && entityID.equals("Beacon")) {
			return true;
		} else {
			return false;
		}
	}
}