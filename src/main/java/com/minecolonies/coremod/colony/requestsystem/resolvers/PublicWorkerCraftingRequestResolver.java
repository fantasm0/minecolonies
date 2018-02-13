package com.minecolonies.coremod.colony.requestsystem.resolvers;

import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Delivery;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.IRecipeStorage;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.constant.TranslationConstants;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.colony.CitizenData;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.buildings.AbstractBuildingWorker;
import com.minecolonies.coremod.colony.jobs.JobDeliveryman;
import com.minecolonies.coremod.colony.jobs.JobSawmill;
import com.minecolonies.coremod.colony.requestsystem.resolvers.core.AbstractCraftingRequestResolver;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

import static com.minecolonies.api.util.RSConstants.CONST_CRAFTING_RESOLVER_PRIORITY;

/**
 * A crafting resolver which takes care of 2x2 crafts which are crafted by the requesting worker.
 */
public class PublicWorkerCraftingRequestResolver extends AbstractCraftingRequestResolver
{
    public PublicWorkerCraftingRequestResolver(@NotNull final ILocation location, @NotNull final IToken<?> token)
    {
        super(location, token, true, false);
    }

    @Override
    public boolean canBuildingCraftStack(@NotNull final AbstractBuildingWorker building, final ItemStack stack)
    {
        return building.getFirstRecipe(stack) != null;
    }

    @Override
    protected boolean canRequestBeAssignedToWorker(
      @NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        if (manager.getColony().getWorld().isRemote)
        {
            return false;
        }

        final Colony colony = (Colony) manager.getColony();
        final CitizenData freeWorker = colony.getCitizenManager().getCitizens()
                                              .stream()
                                              .filter(c -> c.getJob() instanceof JobSawmill)
                                              .findFirst()
                                              .orElse(null);

        return freeWorker != null;
    }

    @Nullable
    @Override
    protected List<IToken<?>> assignRequestToWorker(
      @NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request, @NotNull final List<IToken<?>> currentChildRequests)
    {
        if (manager.getColony().getWorld().isRemote)
        {
            return null;
        }

        final Colony colony = (Colony) manager.getColony();
        final CitizenData freeWorker = colony.getCitizenManager().getCitizens()
                                              .stream()
                                              .filter(c -> c.getJob() instanceof JobSawmill)
                                              .min(Comparator.comparing((CitizenData c) -> ((JobDeliveryman) c.getJob()).getTaskQueue().size())
                                                     .thenComparing(Comparator.comparing(c -> {
                                                         BlockPos targetPos = request.getRequester().getRequesterLocation().getInDimensionLocation();
                                                         //We can do an instant get here, since we are already filtering on anything that has no entity.
                                                         BlockPos entityLocation = c.getCitizenEntity().get().getLocation().getInDimensionLocation();

                                                         return BlockPosUtil.getDistanceSquared(targetPos, entityLocation);
                                                     })))
                                              .orElse(null);

        if (freeWorker != null)
        {
            final JobSawmill jobSawmil = (JobSawmill) freeWorker.getJob();
            jobSawmil.addTask(request.getToken());

            return currentChildRequests;
        }

        return null;
    }

    @Override
    protected void performCraftingForBuilding(@NotNull final AbstractBuildingWorker worker, @NotNull final IRecipeStorage recipeStorage)
    {
        super.performCraftingForBuilding(worker, recipeStorage);
    }

    @Override
    protected void onCraftingCompleted(
      @NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        if (!manager.getColony().getWorld().isRemote)
        {
            final Colony colony = (Colony) manager.getColony();
            final CitizenData worker = colony.getCitizenManager().getCitizens()
                                         .stream()
                                         .filter(c -> c.getJob() instanceof JobSawmill && ((JobSawmill) c.getJob()).getOpenTasks().contains(request.getToken()))
                                         .findFirst()
                                         .orElse(null);

            if (worker == null)
            {
                MineColonies.getLogger().error("Parent cancellation failed! Unknown request: " + request.getToken());
            }
            else
            {
                final JobSawmill job = (JobSawmill) worker.getJob();
                job.finishTask(request.getToken(), true);
            }
        }
    }

    @Nullable
    @Override
    public IToken<?> getFollowupRequestForCompletion(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> completedRequest)
    {
        if (!completedRequest.getRequester().getRequesterLocation().equals(getRequesterLocation()))
        {
            final Delivery delivery = new Delivery(getRequesterLocation(), completedRequest.getRequester().getRequesterLocation(), completedRequest.getRequest().getStack());
            return manager.createRequest(this, delivery);
        }

        return null;
    }

    @Nullable
    @Override
    public IToken<?> onRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        if (!manager.getColony().getWorld().isRemote)
        {
            final Colony colony = (Colony) manager.getColony();
            final CitizenData worker = colony.getCitizenManager().getCitizens()
                                                  .stream()
                                                  .filter(c -> c.getJob() instanceof JobSawmill && ((JobSawmill) c.getJob()).getOpenTasks().contains(request.getToken()))
                                                  .findFirst()
                                                  .orElse(null);

            if (worker == null)
            {
                MineColonies.getLogger().error("Parent cancellation failed! Unknown request: " + request.getToken());
            }
            else
            {
                final JobSawmill job = (JobSawmill) worker.getJob();
                job.onTaskDeletion(request.getToken());
            }
        }

        return null;
    }

    @Override
    public void onRequestBeingOverruled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Stack> request)
    {
        onRequestCancelled(manager, request);
    }

    @Override
    public void onRequestComplete(@NotNull final IRequestManager manager, @NotNull final IToken<?> token)
    {
        manager.updateRequestState(token, RequestState.RECEIVED);
    }

    @Override
    public void onRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IToken<?> token)
    {
        //Noop
    }


    @NotNull
    @Override
    public ITextComponent getDisplayName(@NotNull final IRequestManager manager, @NotNull final IToken<?> token)
    {
        IRequest<?> request = manager.getRequestForToken(token);

        if (request == null)
        {
            return new TextComponentString("<UNKNOWN>");
        }

        return new TextComponentTranslation(TranslationConstants.COM_MINECOLONIES_PUBLIC_CRAFTING_RESOLVER_NAME);
    }

    @Override
    public int getPriority()
    {
        return CONST_CRAFTING_RESOLVER_PRIORITY;
    }
}
