package com.minecolonies.coremod.entity.ai.citizen.builder;

import com.minecolonies.api.util.BlockUtils;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.coremod.blocks.ModBlocks;
import com.minecolonies.coremod.colony.buildings.AbstractBuilding;
import com.minecolonies.coremod.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.coremod.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.coremod.colony.jobs.JobBuilder;
import com.minecolonies.coremod.colony.workorders.WorkOrderBuild;
import com.minecolonies.coremod.colony.workorders.WorkOrderBuildDecoration;
import com.minecolonies.coremod.colony.workorders.WorkOrderBuildRemoval;
import com.minecolonies.coremod.entity.ai.basic.AbstractEntityAIStructureWithWorkOrder;
import com.minecolonies.coremod.entity.ai.util.AIState;
import com.minecolonies.coremod.entity.ai.util.AITarget;
import com.minecolonies.coremod.util.StructureWrapper;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import static com.minecolonies.coremod.entity.ai.util.AIState.*;

/**
 * AI class for the builder.
 * Manages building and repairing buildings.
 */
public class EntityAIStructureBuilder extends AbstractEntityAIStructureWithWorkOrder<JobBuilder>
{
    /**
     * How often should intelligence factor into the builders skill modifier.
     */
    private static final int INTELLIGENCE_MULTIPLIER = 2;

    /**
     * How often should strength factor into the builders skill modifier.
     */
    private static final int STRENGTH_MULTIPLIER = 1;

    /**
     * over this y level the builder will be faster.
     */
    private static final int DEPTH_LEVEL_0 = 60;

    /**
     * At this y level the builder will be slower.
     */
    private static final int DEPTH_LEVEL_1 = 30;

    /**
     * At this y level the builder will be way slower..
     */
    private static final int DEPTH_LEVEL_2 = 15;

    /**
     * Speed buff at 0 depth level.
     */
    private static final double SPEED_BUFF_0 = 0.5;

    /**
     * Speed buff at first depth level.
     */
    private static final int SPEED_BUFF_1 = 2;

    /**
     * Speed buff at second depth level.
     */
    private static final int SPEED_BUFF_2 = 4;

    /**
     * Offset from the building to stand.
     */
    private static final int STAND_OFFSET = 3;

    /**
     * Base y height to start searching a suitable position.
     */
    private static final int BASE_Y_HEIGHT = 70;

    /**
     * After how many actions should the builder dump his inventory.
     */
    private static final int ACTIONS_UNTIL_DUMP = 1024;

    /**
     * Initialize the builder and add all his tasks.
     *
     * @param job the job he has.
     */
    public EntityAIStructureBuilder(@NotNull final JobBuilder job)
    {
        super(job);
        super.registerTargets(
          new AITarget(IDLE, START_WORKING),
          new AITarget(this::checkIfExecute, this::getState),
          new AITarget(START_WORKING, this::startWorkingAtOwnBuilding)
        );
        worker.getCitizenExperienceHandler().setSkillModifier(INTELLIGENCE_MULTIPLIER * worker.getCitizenData().getIntelligence()
                                  + STRENGTH_MULTIPLIER * worker.getCitizenData().getStrength());
        worker.setCanPickUpLoot(true);
    }

    @Override
    public Class getExpectedBuildingClass()
    {
        return BuildingBuilder.class;
    }

    private boolean checkIfExecute()
    {
        setDelay(1);

        if (!job.hasWorkOrder())
        {
            getOwnBuilding(AbstractBuildingStructureBuilder.class).searchWorkOrder();
            return true;
        }

        final WorkOrderBuildDecoration wo = job.getWorkOrder();

        if (wo == null)
        {
            job.setWorkOrder(null);
            return true;
        }

        final AbstractBuilding building = job.getColony().getBuildingManager().getBuilding(wo.getBuildingLocation());
        if (building == null && wo instanceof WorkOrderBuild && !(wo instanceof WorkOrderBuildRemoval))
        {
            job.complete();
            return true;
        }

        if (!job.hasStructure())
        {
            super.initiate();
        }

        return false;
    }

    @Override
    public AIState switchStage(final AIState state)
    {
        if (job.getWorkOrder() instanceof WorkOrderBuildRemoval && state.equals(BUILDING_STEP))
        {
            return COMPLETE_BUILD;
        }
        return super.switchStage(state);
    }


    private AIState startWorkingAtOwnBuilding()
    {
        if (walkToBuilding())
        {
            return getState();
        }
        return START_BUILDING;
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes a min distance from width and length.
     * <p>
     * Then finds the floor level at that distance and then check if it does contain two air levels.
     *
     * @param targetPosition the position to work at.
     * @return BlockPos position to work from.
     */
    @Override
    public BlockPos getWorkingPosition(final BlockPos targetPosition)
    {
        final StructureWrapper wrapper = job.getStructure();
        final int x1 = wrapper.getPosition().getX() - wrapper.getOffset().getX() - STAND_OFFSET;
        final int z1 = wrapper.getPosition().getZ() - wrapper.getOffset().getZ() - STAND_OFFSET;
        final int x3 = wrapper.getPosition().getX() + (wrapper.getWidth() - wrapper.getOffset().getX()) + STAND_OFFSET;
        final int z3 = wrapper.getPosition().getZ() + (wrapper.getLength() - wrapper.getOffset().getZ() + STAND_OFFSET);

        final BlockPos[] edges = new BlockPos[] {
          new BlockPos(x1, BASE_Y_HEIGHT, z1),
          new BlockPos(x3, 70, z1),
          new BlockPos(x1, BASE_Y_HEIGHT, z3),
          new BlockPos(x3, BASE_Y_HEIGHT, z3)};

        for (final BlockPos pos : edges)
        {
            final BlockPos basePos = world.getTopSolidOrLiquidBlock(pos);
            if (EntityUtils.checkForFreeSpace(world, basePos.down())
                  && world.getBlockState(basePos).getBlock() != Blocks.SAPLING
                  && world.getBlockState(basePos.down()).getMaterial().isSolid())
            {
                return basePos;
            }
        }
        return targetPosition;
    }

    @Override
    public void connectBlockToBuildingIfNecessary(@NotNull final IBlockState blockState, @NotNull final BlockPos pos)
    {
        final BlockPos buildingLocation = job.getWorkOrder().getBuildingLocation();
        final AbstractBuilding building = this.getOwnBuilding().getColony().getBuildingManager().getBuilding(buildingLocation);

        if (building != null)
        {
            building.registerBlockPosition(blockState, pos, world);
        }

        if (blockState.getBlock() == ModBlocks.blockWayPoint)
        {
            worker.getCitizenColonyHandler().getColony().addWayPoint(pos, world.getBlockState(pos));
        }
    }

    @Override
    public boolean shallReplaceSolidSubstitutionBlock(final Block worldBlock, final IBlockState worldMetadata)
    {
        return false;
    }

    @Override
    public IBlockState getSolidSubstitution(@NotNull final BlockPos location)
    {
        return BlockUtils.getSubstitutionBlockAtWorld(world, location);
    }

    @Override
    public int getBlockMiningDelay(@NotNull final Block block, @NotNull final BlockPos pos)
    {
        final int initialDelay = super.getBlockMiningDelay(block, pos);

        if (pos.getY() > DEPTH_LEVEL_0)
        {
            return (int) (initialDelay * SPEED_BUFF_0);
        }

        if (pos.getY() > DEPTH_LEVEL_1)
        {
            return initialDelay;
        }

        if (pos.getY() < DEPTH_LEVEL_2)
        {
            return initialDelay * SPEED_BUFF_2;
        }
        return initialDelay * SPEED_BUFF_1;
    }

    /**
     * Calculates after how many actions the ai should dump it's inventory.
     *
     * @return the number of actions done before item dump.
     */
    @Override
    protected int getActionsDoneUntilDumping()
    {
        return ACTIONS_UNTIL_DUMP;
    }
}
