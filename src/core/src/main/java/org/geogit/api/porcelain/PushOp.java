/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.api.porcelain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.GlobalContextBuilder;
import org.geogit.api.Ref;
import org.geogit.api.Remote;
import org.geogit.api.SymRef;
import org.geogit.api.plumbing.ForEachRef;
import org.geogit.api.plumbing.RefParse;
import org.geogit.api.plumbing.UpdateRef;
import org.geogit.api.porcelain.SynchronizationException.StatusCode;
import org.geogit.remote.IRemoteRepo;
import org.geogit.remote.RemoteUtils;
import org.geogit.repository.Hints;
import org.geogit.repository.Repository;
import org.geogit.storage.DeduplicationService;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;

/**
 * Update remote refs along with associated objects.
 * 
 * <b>NOTE:</b> so far we don't have the ability to merge non conflicting changes. Instead, the diff
 * list we get acts on whole objects, , so its possible that this operation overrites non
 * conflicting changes when pushing a branch that has non conflicting changes at both sides. This
 * needs to be revisited once we get more merge tools.
 */
public class PushOp extends AbstractGeoGitOp<Boolean> {

    private boolean all;

    private List<String> refSpecs = new ArrayList<String>();

    private Supplier<Optional<Remote>> remote;

    /**
     * @param all if {@code true}, push all refs under refs/heads/
     * @return {@code this}
     */
    public PushOp setAll(final boolean all) {
        this.all = all;
        return this;
    }

    /**
     * @param refSpec the refspec of a remote branch
     * @return {@code this}
     */
    public PushOp addRefSpec(final String refSpec) {
        refSpecs.add(refSpec);
        return this;
    }

    /**
     * @param remoteName the name or URL of a remote repository to push to
     * @return {@code this}
     */
    public PushOp setRemote(final String remoteName) {
        Preconditions.checkNotNull(remoteName);
        return setRemote(command(RemoteResolve.class).setName(remoteName));
    }

    /**
     * @param remoteSupplier a supplier for the remote repository to push to
     * @return {@code this}
     */
    public PushOp setRemote(Supplier<Optional<Remote>> remoteSupplier) {
        Preconditions.checkNotNull(remoteSupplier);
        remote = remoteSupplier;

        return this;
    }

    /**
     * Executes the push operation.
     * 
     * @return {@code null}
     * @see org.geogit.api.AbstractGeoGitOp#call()
     */
    @Override
    protected Boolean _call() {
        if (remote == null) {
            setRemote("origin");
        }

        Optional<Remote> pushRemote = remote.get();

        Preconditions.checkArgument(pushRemote.isPresent(), "Remote could not be resolved.");

        Optional<IRemoteRepo> remoteRepo = getRemoteRepo(pushRemote.get());

        Preconditions.checkState(remoteRepo.isPresent(), "Failed to connect to the remote.");

        try {
            remoteRepo.get().open();
        } catch (IOException e) {
            Throwables.propagate(e);
        }

        boolean dataPushed = false;
        try {
            if (refSpecs.size() > 0) {
                for (String refspec : refSpecs) {
                    String[] refs = refspec.split(":");
                    if (refs.length == 0) {
                        refs = new String[2];
                        refs[0] = "";
                        refs[1] = "";
                    }
                    Preconditions.checkArgument(refs.length < 3,
                            "Invalid refspec, please use [+][<localref>][:][<remoteref>].");

                    boolean force = refspec.length() > 0 && refspec.charAt(0) == '+';
                    String localrefspec = refs[0].substring(force ? 1 : 0);

                    String remoterefspec = (refs.length == 2 ? refs[1] : localrefspec);

                    if (localrefspec.equals("")) {
                        if (!remoterefspec.equals("")) {
                            // delete the remote branch matching remoteref
                            remoteRepo.get().deleteRef(remoterefspec);
                        } else {
                            // push current branch
                            final Optional<Ref> currHead = command(RefParse.class)
                                    .setName(Ref.HEAD).call();
                            Preconditions.checkState(currHead.isPresent(),
                                    "Repository has no HEAD, can't push.");
                            Preconditions.checkState(currHead.get() instanceof SymRef,
                                    "Can't push from detached HEAD");
                            final SymRef headRef = (SymRef) currHead.get();
                            final Optional<Ref> targetRef = command(RefParse.class).setName(
                                    headRef.getTarget()).call();
                            Preconditions.checkState(targetRef.isPresent());
                            remoteRepo.get().pushNewData(targetRef.get());
                            this.command(UpdateRef.class)
                                    .setNewValue(targetRef.get().getObjectId())
                                    .setName(
                                            pushRemote.get().getName() + "/"
                                                    + targetRef.get().localName()).call();

                            this.command(UpdateRef.class)
                                    .setNewValue(targetRef.get().getObjectId())
                                    .setName(currHead.get().getName());
                        }
                    } else {
                        Optional<Ref> localRef = command(RefParse.class).setName(localrefspec)
                                .call();
                        Preconditions.checkArgument(localRef.isPresent(),
                                "Local ref could not be resolved.");
                        // push the localref branch to the remoteref branch
                        try {
                            remoteRepo.get().pushNewData(localRef.get(), remoterefspec);
                            command(UpdateRef.class).setNewValue(localRef.get().getObjectId())
                                    .setName(pushRemote.get().getName() + "/" + remoterefspec)
                                    .call();
                            dataPushed = true;
                        } catch (SynchronizationException e) {
                            if (e.statusCode != StatusCode.NOTHING_TO_PUSH) {
                                Throwables.propagate(e);
                            }
                        }
                    }

                }
            } else {
                List<Ref> refsToPush = new ArrayList<Ref>();
                if (all) {
                    Predicate<Ref> filter = new Predicate<Ref>() {
                        final String prefix = Ref.HEADS_PREFIX;

                        @Override
                        public boolean apply(Ref input) {
                            return input.getName().startsWith(prefix);
                        }
                    };
                    refsToPush.addAll(command(ForEachRef.class).setFilter(filter).call());
                } else {
                    // push current branch
                    final Optional<Ref> currHead = command(RefParse.class).setName(Ref.HEAD).call();
                    Preconditions.checkState(currHead.isPresent(),
                            "Repository has no HEAD, can't push.");
                    Preconditions.checkState(currHead.get() instanceof SymRef,
                            "Can't push from detached HEAD");
                    final SymRef headRef = (SymRef) currHead.get();
                    final Optional<Ref> targetRef = command(RefParse.class).setName(
                            headRef.getTarget()).call();
                    Preconditions.checkState(targetRef.isPresent());
                    refsToPush.add(targetRef.get());
                }

                for (Ref ref : refsToPush) {
                    try {
                        remoteRepo.get().pushNewData(ref);
                        this.command(UpdateRef.class).setNewValue(ref.getObjectId())
                                .setName(pushRemote.get().getName() + "/" + ref.localName()).call();
                        dataPushed = true;
                    } catch (SynchronizationException e) {
                        if (e.statusCode != StatusCode.NOTHING_TO_PUSH) {
                            Throwables.propagate(e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            try {
                remoteRepo.get().close();
            } catch (IOException e) {
                Throwables.propagate(e);
            }
        }

        return dataPushed;
    }

    /**
     * @param remote the remote to get
     * @return an interface for the remote repository
     */
    public Optional<IRemoteRepo> getRemoteRepo(Remote remote) {
        Hints remoteHints = new Hints();
        remoteHints.set(Hints.REMOTES_READ_ONLY, Boolean.FALSE);
        Repository localRepository = repository();
        DeduplicationService deduplicationService = context.deduplicationService();
        return RemoteUtils.newRemote(GlobalContextBuilder.builder.build(remoteHints), remote,
                localRepository, deduplicationService);
    }
}
