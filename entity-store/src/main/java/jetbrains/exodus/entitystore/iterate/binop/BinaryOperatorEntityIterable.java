/**
 * Copyright 2010 - 2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.iterate.binop;

import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.EntityIterableHandle;
import jetbrains.exodus.entitystore.EntityIterableType;
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl;
import jetbrains.exodus.entitystore.iterate.EntityIterableBase;
import jetbrains.exodus.entitystore.iterate.EntityIterableHandleBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings({"ProtectedField", "RawUseOfParameterizedType", "MethodOnlyUsedFromInnerClass"})
abstract class BinaryOperatorEntityIterable extends EntityIterableBase {

    private static final int MAXIMUM_DEPTH_TO_ALLOW_CACHING = 200;
    private static final int COMMUTATIVE_FLAG = 1 << 30;
    protected static final int SORTED_BY_ID_FLAG = 1 << 29;
    private static final int CAN_BE_CACHED_FLAG = 1 << 28;
    private static final int DEPTH_MASK = CAN_BE_CACHED_FLAG - 1;

    @NotNull
    protected final EntityIterableBase iterable1;
    @NotNull
    protected final EntityIterableBase iterable2;
    protected int depth;

    @SuppressWarnings({"ThrowableInstanceNeverThrown"})
    protected BinaryOperatorEntityIterable(@Nullable final PersistentEntityStoreImpl store,
                                           @NotNull final EntityIterableBase iterable1,
                                           @NotNull final EntityIterableBase iterable2,
                                           final boolean isCommutative) {
        super(store);
        final int depth1 = iterable1.depth();
        final int depth2 = iterable2.depth();
        // for commutative operations, try to build right-oriented tree
        if (!isCommutative || depth1 < depth2) {
            this.iterable1 = iterable1;
            this.iterable2 = iterable2;
        } else {
            this.iterable1 = iterable2;
            this.iterable2 = iterable1;
        }
        depth = depth1 > depth2 ? depth1 + 1 : depth2 + 1;
        if (isCommutative) {
            depth += COMMUTATIVE_FLAG;
        }
        if (depth() < MAXIMUM_DEPTH_TO_ALLOW_CACHING && iterable1.canBeCached() && iterable2.canBeCached()) {
            depth += CAN_BE_CACHED_FLAG;
        }
    }

    @NotNull
    public EntityIterableBase getLeft() {
        return iterable1;
    }

    @NotNull
    public EntityIterableBase getRight() {
        return iterable2;
    }

    @Override
    public boolean setOrigin(Object origin) {
        if (super.setOrigin(origin)) {
            iterable1.setOrigin(origin);
            iterable2.setOrigin(origin);
            return true;
        }
        return false;
    }

    @Override
    public boolean isSortedById() {
        return (depth & SORTED_BY_ID_FLAG) != 0;
    }

    @Override
    public int depth() {
        return depth & DEPTH_MASK;
    }

    @Override
    public boolean canBeCached() {
        return (depth & CAN_BE_CACHED_FLAG) != 0;
    }

    protected final boolean isCommutative() {
        return (depth & COMMUTATIVE_FLAG) != 0;
    }

    protected abstract EntityIterableType getIterableType();

    @Override
    @NotNull
    protected EntityIterableHandle getHandleImpl() {
        return new EntityIterableHandleBase(getStore(), getIterableType()) {
            @Nullable
            private final int[] linkIds = mergeLinkIds(iterable1.getHandle().getLinkIds(), iterable2.getHandle().getLinkIds());

            @Nullable
            @Override
            public int[] getLinkIds() {
                return linkIds;
            }

            @Override
            public void getStringHandle(@NotNull final StringBuilder builder) {
                super.getStringHandle(builder);
                builder.append('-');
                final EntityIterableHandleBase handle1 = (EntityIterableHandleBase) iterable1.getHandle();
                final EntityIterableHandleBase handle2 = (EntityIterableHandleBase) iterable2.getHandle();
                if (!isCommutative() || isOrderOk(handle1, handle2)) {
                    appendStringBuilderWithHandle(builder, handle1);
                    builder.append('-');
                    appendStringBuilderWithHandle(builder, handle2);
                } else {
                    appendStringBuilderWithHandle(builder, handle2);
                    builder.append('-');
                    appendStringBuilderWithHandle(builder, handle1);
                }
            }

            @Override
            public boolean isMatchedEntityAdded(@NotNull final EntityId added) {
                return iterable1.getHandle().isMatchedEntityAdded(added) ||
                        iterable2.getHandle().isMatchedEntityAdded(added);
            }

            @Override
            public boolean isMatchedEntityDeleted(@NotNull final EntityId deleted) {
                return iterable1.getHandle().isMatchedEntityDeleted(deleted) ||
                        iterable2.getHandle().isMatchedEntityDeleted(deleted);
            }

            @Override
            public boolean isMatchedLinkAdded(@NotNull final EntityId source,
                                              @NotNull final EntityId target,
                                              final int linkId) {
                final EntityIterableHandle handle1 = iterable1.getHandle();
                final EntityIterableHandle handle2;
                if (handle1.hasLinkId(linkId)) {
                    if (handle1.isMatchedLinkAdded(source, target, linkId)) {
                        return true;
                    }
                    handle2 = iterable2.getHandle();
                    if (!handle2.hasLinkId(linkId)) {
                        return false;
                    }
                } else {
                    handle2 = iterable2.getHandle();
                }
                return handle2.isMatchedLinkAdded(source, target, linkId);
            }

            @Override
            public boolean isMatchedLinkDeleted(@NotNull EntityId source, @NotNull EntityId target, int linkId) {
                final EntityIterableHandle handle1 = iterable1.getHandle();
                final EntityIterableHandle handle2;
                if (handle1.hasLinkId(linkId)) {
                    if (handle1.isMatchedLinkDeleted(source, target, linkId)) {
                        return true;
                    }
                    handle2 = iterable2.getHandle();
                    if (!handle2.hasLinkId(linkId)) {
                        return false;
                    }
                } else {
                    handle2 = iterable2.getHandle();
                }
                return handle2.isMatchedLinkDeleted(source, target, linkId);

            }

            @Override
            public boolean isMatchedPropertyChanged(final int typeId,
                                                    final int propertyId,
                                                    @Nullable final Comparable oldValue,
                                                    @Nullable final Comparable newValue) {
                return iterable1.getHandle().isMatchedPropertyChanged(typeId, propertyId, oldValue, newValue) ||
                        iterable2.getHandle().isMatchedPropertyChanged(typeId, propertyId, oldValue, newValue);
            }

            @Override
            public boolean isExpired() {
                return iterable1.getHandle().isExpired() || iterable2.getHandle().isExpired();
            }
        };
    }

    private static boolean isOrderOk(@NotNull final EntityIterableHandle handle1,
                                     @NotNull final EntityIterableHandle handle2) {
        final int type1 = handle1.getType().getType();
        final int type2 = handle2.getType().getType();
        return type1 < type2 || type1 == type2 && handle1.hashCode() < handle2.hashCode();
    }

    private static void appendStringBuilderWithHandle(@NotNull final StringBuilder builder,
                                                      @NotNull final EntityIterableHandleBase handle) {
        final String cachedStringHandle = handle.getCachedStringHandle();
        if (cachedStringHandle != null) {
            builder.append(cachedStringHandle);
        } else {
            handle.getStringHandle(builder);
        }
    }
}