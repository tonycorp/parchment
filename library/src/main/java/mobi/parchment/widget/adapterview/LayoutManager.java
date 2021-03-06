package mobi.parchment.widget.adapterview;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mobi.parchment.widget.adapterview.snapposition.CenterSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.EndSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.EndWithCellSpacingSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.OnScreenSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.OnScreenWithCellSpacingSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.SnapPositionInterface;
import mobi.parchment.widget.adapterview.snapposition.StartSnapPosition;
import mobi.parchment.widget.adapterview.snapposition.StartWithCellSpacingSnapPosition;


public abstract class LayoutManager<Cell> extends AdapterViewDataSetObserver {
    public static final int INVALID_POSITION = -1;

    private final Map<View, Integer> mPositions = new HashMap<View, Integer>();

    private int mAnimationId = -1;
    private int mOffset = 0;
    private int mStartCellPosition;
    private int mViewPageDistance;
    private int mAnimationDisplacement;
    private int mCenteringOffset = 0;
    protected final ViewGroup mViewGroup;
    private final ScrollDirectionManager mScrollDirectionManager;

    protected final SelectedPositionManager mSelectedPositionManager;
    private AnimationStoppedListener mAnimationStoppedListener;
    private final LayoutManagerAttributes mLayoutManagerAttributes;

    protected final List<Cell> mCells = new ArrayList<Cell>();
    private final SnapPositionInterface<Cell> mSnapPositionInterface;
    private View mPressedView;

    public LayoutManager(final ViewGroup viewGroup, final OnSelectedListener onSelectedListener, final AdapterViewManager adapterViewManager, final LayoutManagerAttributes layoutManagerAttributes) {
        super(adapterViewManager);
        mViewGroup = viewGroup;
        mStartCellPosition = 0;
        mSelectedPositionManager = new SelectedPositionManager(onSelectedListener);
        mScrollDirectionManager = new ScrollDirectionManager(layoutManagerAttributes);
        mLayoutManagerAttributes = layoutManagerAttributes;

        final boolean isCircularScroll = mLayoutManagerAttributes.isCircularScroll();
        final SnapPosition snapPosition = getSnapPosition(isCircularScroll);
        mSnapPositionInterface = getSnapPositionInterface(snapPosition);
    }

    private SnapPositionInterface<Cell> getSnapPositionInterface(final SnapPosition snapPosition) {
        switch (snapPosition) {
            case center:
                return new CenterSnapPosition<Cell>();
            case floatEnd:
                return new EndSnapPosition<Cell>();
            case floatEndWithCellSpacing:
                return new EndWithCellSpacingSnapPosition<Cell>();
            case floatStart:
                return new StartSnapPosition<Cell>();
            case floatStartWithCellSpacing:
                return new StartWithCellSpacingSnapPosition<Cell>();
            case onScreen:
                return new OnScreenSnapPosition<Cell>();
            case onScreenWithCellSpacing:
            default:
                return new OnScreenWithCellSpacingSnapPosition<Cell>();
        }
    }

    protected int getCellSpacing() {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        return cellSpacing;
    }

    protected ViewGroup getViewGroup() {
        return mViewGroup;
    }

    protected int getViewStart(final View view) {
        return mScrollDirectionManager.getViewStart(view);
    }

    protected int getViewEnd(final View view) {
        return mScrollDirectionManager.getViewEnd(view);
    }

    protected int getViewSize(final View view) {
        return mScrollDirectionManager.getViewSize(view);
    }

    protected int getViewBreadth(final View view) {
        return mScrollDirectionManager.getViewBreadth(view);
    }

    @Override
    public void destroy() {
        mPositions.clear();
        super.destroy();
        mCells.clear();
    }

    public void measure(final ViewGroup viewGroup) {
        for (final Cell cell : mCells) {
            measure(cell, viewGroup);
        }
    }

    public abstract View getLastView(final Cell cell);

    public abstract View getView(final Cell cell);

    public abstract View getFirstView(final Cell cell);

    public abstract int getCellStart(final Cell cell);

    public abstract int getCellEnd(final Cell cell);

    public abstract int getCellSize(final Cell cell);

    public abstract List<View> getViews(final Cell cell);

    public abstract Cell getCell(final int adapterPosition);

    public abstract void layoutCell(final Cell cell, final int cellStart, final int cellEnd, final int firstAdapterPositionInCell, final int breadth, final int cellSpacing);

    public abstract void measure(final Cell cell, final ViewGroup viewGroup);

    public abstract View getLastAdapterPositionView(final Cell cell);

    public abstract View getFirstAdapterPositionView(final Cell cell);

    protected abstract int getCellCount();

    protected abstract int getMaxMeasureHeight(final int position);

    protected abstract int getMaxMeasureWidth(final int position);

    protected abstract int getCellPosition(final int adapterPosition);

    protected abstract int getFirstAdapterPositionInCell(final int cellPosition);

    protected abstract int getDrawPosition(final List<Cell> cells, final int drawCellPosition);

    /**
     * @param animation Positive displacement moves the screen to the right and vice versa
     * @param changed
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
    public void layout(final AdapterViewHandler adapterViewHandler, final Animation animation, final boolean changed, final int left, final int top, final int right, final int bottom) {

        mCenteringOffset = 0;
        if (mAdapterViewManager.getAdapterCount() == 0) return;

        final int size = mScrollDirectionManager.getDrawSize(left, top, right, bottom);
        final int displacement = animation.getDisplacement();

        final int animationId = animation.getId();
        final boolean continuedAnimation = animationId == mAnimationId;

        if (!continuedAnimation && !mCells.isEmpty()) {
            mAnimationId = animationId;
            mViewPageDistance = getViewPageDistance(size);
            mAnimationDisplacement = displacement;
        } else if (continuedAnimation) mAnimationDisplacement += displacement;

        final int newSize = size; //Todo: consider padding for newSize
        final int adjust = setOffset(displacement, newSize);

        if (continuedAnimation) mAnimationDisplacement += adjust;

        final int breadth = mScrollDirectionManager.getDrawBreadth(left, top, right, bottom);
        layoutCells(adapterViewHandler, newSize, breadth, mCenteringOffset);

        if (needLayout(newSize, getCellSpacing(), displacement)) {
            if (mAnimationStoppedListener != null) {
                mAnimationStoppedListener.onAnimationStopped();
            }
            mAnimationDisplacement = 0;
            setOffset(0, newSize);
            layoutCells(adapterViewHandler, newSize, breadth, mCenteringOffset);
        }

        checkSelectWhileScrollingAttribute(newSize);
        mSelectedPositionManager.onViewsDrawn(mPositions);
    }


    private boolean needLayout(final int size, final int cellSpacing, final int scrollDisplacement) {
        final int adapterCount = mAdapterViewManager.getAdapterCount();
        final boolean haveCellsToDraw = adapterCount > 0;
        final boolean noCellsBeingDrawn = mCells.isEmpty();
        Move direction = Move.none;

        if (scrollDisplacement < 0) {
            direction = Move.back;
        } else if (scrollDisplacement > 0) {
            direction = Move.forward;
        }

        if (haveCellsToDraw && noCellsBeingDrawn) {

            if (scrollDisplacement > 0) { //too far to the right
                final Cell cell = getCell(0);
                final int cellSize = getCellSize(cell);
                final int absoluteSnapPosition = mSnapPositionInterface.getAbsoluteSnapPosition(size, cellSpacing, cellSize, direction);
                mOffset = absoluteSnapPosition;
                mStartCellPosition = 0;
            } else if (scrollDisplacement < 0) { //too far to the left
                final Cell cell = getCell(adapterCount - 1);
                final int cellSize = getCellSize(cell);
                final int absoluteSnapPosition = mSnapPositionInterface.getAbsoluteSnapPosition(size, cellSpacing, cellSize, direction);
                mOffset = absoluteSnapPosition;
                mStartCellPosition = getCellCount() - 1;
            }
            return true;
        }

        final Cell firstPosition = getFirstCell();
        final Cell lastPosition = getLastCell();

        final boolean firstAndLastNotOnScreen = firstPosition == null && lastPosition == null;
        if (firstAndLastNotOnScreen) {
            return false;
        }

        if (firstPosition != null) {
            final int displacement = mSnapPositionInterface.getCellDisplacementFromSnapPosition(this, size, firstPosition, cellSpacing);
            if (displacement < 0) {
                mStartCellPosition = 0;
                mOffset += displacement;
                return true;
            }
        }

        if (lastPosition != null) {
            final int displacement = mSnapPositionInterface.getCellDisplacementFromSnapPosition(this, size, lastPosition, cellSpacing);
            if (displacement > 0) {
                mStartCellPosition = getCellCount() - 1;
                mOffset += displacement;
                return true;
            }
        }

        return false;

    }

    private Cell getFirstCell() {
        if (mStartCellPosition == 0) {
            return mCells.get(0);
        }
        return null;
    }

    private Cell getLastCell() {
        final int size = mCells.size();
        final int cellCount = getCellCount();
        if (cellCount == -1 || mStartCellPosition + size != cellCount) {
            return null;
        }

        return mCells.get(size - 1);
    }

    private void checkSelectWhileScrollingAttribute(final int newWidth) {
        final boolean shouldSelectWhileScrolling = mLayoutManagerAttributes.selectWhileScrolling() && !mLayoutManagerAttributes.isSnapPositionOnScreen();
        if (shouldSelectWhileScrolling) {
            final View view = getNearestViewToSnapPosition(newWidth);
            final int position = getPosition(view);
            mSelectedPositionManager.setSelectedPosition(position);
        }
    }

    private int getViewPageDistance(final int size) {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        int viewPageDistance = 0;
        for (final Cell cell : mCells) {
            final int start = getCellStart(cell) - cellSpacing;
            final int end = getCellEnd(cell) + cellSpacing;

            if (start >= 0 && end <= size) {
                final int viewSize = end - start;
                viewPageDistance += viewSize;
            } else if (start < 0 && end > size) {
                final int viewSize = end - start;
                return viewSize;
            }
        }

        final boolean calculateFirstView = !mCells.isEmpty() && viewPageDistance == 0;
        if (calculateFirstView) {
            final Cell cell = mCells.get(0);
            final int start = getCellStart(cell) - cellSpacing;
            final int end = getCellEnd(cell) + cellSpacing;
            viewPageDistance = end - start;
        }

        return viewPageDistance;
    }

    private View getNearestViewToSnapPosition(final int size) {
        Cell nearestCell = null;
        int nearestCellDistance = Integer.MAX_VALUE;

        for (final Cell currentCell : mCells) {
            final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
            final int currentViewDistance = mSnapPositionInterface.getCellDistanceFromSnapPosition(this, size, currentCell, cellSpacing);
            final boolean currentViewIsCloser = currentViewDistance < nearestCellDistance;

            if (currentViewIsCloser) {
                nearestCellDistance = currentViewDistance;
                nearestCell = currentCell;
            }
        }
        if (nearestCell == null) {
            return null;
        }

        return getView(nearestCell);
    }


    private int setOffset(final int displacement, final int size) {
        final boolean isCircularScroll = mLayoutManagerAttributes.isCircularScroll();

        final int overDrawAdjust = getOverDrawAdjust(isCircularScroll, size, displacement);
        mOffset += displacement + overDrawAdjust;
        return overDrawAdjust;
    }

    private SnapPosition getSnapPosition(final boolean isCircularScroll) {
        if (isCircularScroll) return SnapPosition.onScreenWithCellSpacing;
        return mLayoutManagerAttributes.getSnapPosition();
    }


    public int getCellSizeTotal() {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing() * 2;
        int viewSizeTotal = 0;
        for (final Cell cell : mCells)
            viewSizeTotal += getCellSize(cell) + cellSpacing;
        return viewSizeTotal;
    }

    private int getOverDrawAdjust(final boolean isCircularScroll, final int size, final int displacement) {
        final boolean viewsBeingDrawn = !mCells.isEmpty();
        if (!viewsBeingDrawn || isCircularScroll) return 0;

        final boolean isViewPager = mLayoutManagerAttributes.isViewPager();
        if (isViewPager && Math.abs(mAnimationDisplacement) > mViewPageDistance)
            return -displacement;

        final Move move = getMove(displacement);
        switch (move) {
            case none:
            case back:
                return getMoveBackwardOverDrawAdjust(size, displacement);
            case forward:
                return getMoveForwardOverDrawAdjust(size, displacement);
        }

        return 0;
    }


    private int getMoveBackwardOverDrawAdjust(final int size, final int displacement) {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        final int index = mCells.size() - 1;

        final Cell cell = mCells.get(index);
        final View view = getLastAdapterPositionView(cell);

        final int position = mPositions.get(view);
        final boolean isLastPosition = position == mAdapterViewManager.getAdapterCount() - 1;
        if (!isLastPosition) {
            return 0;
        }

        final int drawLimit = mSnapPositionInterface.getDrawLimitMoveBackwardOverDrawAdjust(this, mCells, size, cell, cellSpacing);

        final int startMostPixel = getCellStart(cell) + displacement;
        final boolean isOverDrawn = startMostPixel < drawLimit;
        if (!isOverDrawn) {
            return 0;
        }

        final boolean selectOnSnap = mLayoutManagerAttributes.selectOnSnap();
        final boolean snapToPosition = mLayoutManagerAttributes.isSnapToPosition();
        if (isOverDrawn) {
            onAnimationStopped();
            if (selectOnSnap && snapToPosition) {
                final View selectedView = getView(cell);
                setSelected(selectedView);
            }
        }

        final int overDrawAdjust = drawLimit - startMostPixel;
        return overDrawAdjust;
    }

    private int getMoveForwardOverDrawAdjust(final int size, final int displacement) {

        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        final int index = 0;
        final Cell cell = mCells.get(index);

        final View firstViewInCell = getFirstAdapterPositionView(cell);

        final int position = mPositions.get(firstViewInCell);
        final boolean isFirstPosition = position == 0;
        if (!isFirstPosition) {
            return 0;
        }

        final int drawLimit = mSnapPositionInterface.getDrawLimitMoveForwardOverDrawAdjust(this, mCells, size, cell, cellSpacing);

        final int endMostPixel = getCellEnd(cell) + displacement;
        final boolean isOverDrawn = endMostPixel > drawLimit;
        if (!isOverDrawn) {
            return 0;
        }

        final boolean selectOnSnap = mLayoutManagerAttributes.selectOnSnap();
        final boolean snapToPosition = mLayoutManagerAttributes.isSnapToPosition();
        if (isOverDrawn) {
            onAnimationStopped();
            if (selectOnSnap && snapToPosition) {
                final View selectedView = getView(cell);
                setSelected(selectedView);
            }
        }

        final int overDrawAdjust = drawLimit - endMostPixel;
        return overDrawAdjust;
    }

    public View getSnapDistanceToNearestView(final int size) {
        if (mCells.size() == 0) return null;

        final SnapPosition snapPosition = mLayoutManagerAttributes.getSnapPosition();
        final boolean snapPositionIsOnScreen = snapPosition == SnapPosition.onScreen || snapPosition == SnapPosition.onScreenWithCellSpacing;
        if (snapPositionIsOnScreen) return null;

        final View nearestView = getNearestViewToSnapPosition(size);
        return nearestView;
    }

    public int getCellCenter(final Cell cell) {
        return (getCellStart(cell) + getCellEnd(cell)) / 2;
    }

    public int getSnapToPixelDistance(final int size, final View view) {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        return mSnapPositionInterface.getSnapToPixelDistance(this, mScrollDirectionManager, size, view, cellSpacing);
    }


    public int getAdapterCount() {
        return mAdapterViewManager.getAdapterCount();
    }

    /**
     * When moving left, every time a view is removed, this means that we are removing the leftMost view and therefore have to increment the mOffset by the removed view's width
     */
    private void layoutCells(final AdapterViewHandler adapterViewHandler, final int size, final int breadth, final int centerOffset) {
        final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();
        int currentOffset = mOffset;
        int endCellPosition = mStartCellPosition;

        for (final Cell cell : new ArrayList<Cell>(mCells)) {
            final int cellStart = currentOffset + cellSpacing;
            final int cellSize = getCellSize(cell);
            final int cellEnd = cellStart + cellSize;

            currentOffset = cellEnd;
            final boolean cellIsOffScreenBehind = currentOffset < centerOffset;
            final boolean cellIsOffScreenAhead = cellStart - cellSpacing > size + centerOffset;

            if (cellIsOffScreenBehind) {
                mOffset = currentOffset;
                mCells.remove(cell);

                final List<View> viewsToRemove = getViews(cell);
                for (final View view : viewsToRemove) {
                    adapterViewHandler.removeViewInAdapterView(view);
                    mAdapterViewManager.recycle(view);
                    mPositions.remove(view);
                }

                incrementStartCellPosition();
                endCellPosition = incrementCellPosition(endCellPosition);
            } else if (cellIsOffScreenAhead) {
                mCells.remove(cell);

                final List<View> viewsToRemove = getViews(cell);
                for (final View view : viewsToRemove) {
                    adapterViewHandler.removeViewInAdapterView(view);
                    mAdapterViewManager.recycle(view);
                    mPositions.remove(view);
                }
            } else {
                final int firstViewInCell = getFirstAdapterPositionInCell(endCellPosition);
                layoutCell(cell, cellStart, cellEnd, firstViewInCell, breadth, cellSpacing);
                endCellPosition = incrementCellPosition(endCellPosition);
            }
        }

        while (currentOffset + cellSpacing <= size + centerOffset) {
            final int firstAdapterPosition = getFirstAdapterPositionInCell(endCellPosition);
            final int adapterCount = mAdapterViewManager.getAdapterCount();
            final boolean aboveCount = firstAdapterPosition >= adapterCount;
            if (aboveCount) break;

            final boolean isPositionBeingDrawn = isPositionBeingDrawn(firstAdapterPosition);
            if (isPositionBeingDrawn) break;

            final Cell cell = getCell(firstAdapterPosition);

            final int cellStart = currentOffset + cellSpacing;
            final int cellSize = getCellSize(cell);
            final int cellEnd = cellStart + cellSize;
            currentOffset = cellEnd;

            final boolean removeCell = currentOffset < centerOffset;

            if (removeCell) {
                mOffset = currentOffset;
                final List<View> views = getViews(cell);
                for (final View view : views) {
                    mAdapterViewManager.recycle(view);
                }
                incrementStartCellPosition();
            } else {

                layoutCell(cell, cellStart, cellEnd, firstAdapterPosition, breadth, cellSpacing);

                final int drawCellPosition = mCells.size();
                int drawPosition = getDrawPosition(mCells, drawCellPosition);
                int position = firstAdapterPosition;
                final List<View> views = getViews(cell);
                for (final View view : views) {
                    adapterViewHandler.addViewInAdapterView(view, drawPosition++, view.getLayoutParams());
                    mPositions.put(view, position++);
                }

                mCells.add(cell);
            }

            endCellPosition = incrementCellPosition(endCellPosition);
        }

        currentOffset = mOffset;
        int cellPosition = decrementCellPosition(mStartCellPosition);

        while (currentOffset > centerOffset) {
            final int adapterPosition = getFirstAdapterPositionInCell(cellPosition);
            final boolean belowCount = adapterPosition < 0;
            if (belowCount) break;

            final boolean isPositionBeingDrawn = isPositionBeingDrawn(adapterPosition);
            if (isPositionBeingDrawn) break;

            final Cell cell = getCell(adapterPosition);

            final int cellEnd = currentOffset;
            final int cellSize = getCellSize(cell);
            final int cellStart = cellEnd - cellSize;
            currentOffset = cellStart - cellSpacing;

            final boolean cellIsOnScreen = currentOffset <= size + centerOffset;

            if (cellIsOnScreen) {
                layoutCell(cell, cellStart, cellEnd, cellPosition, breadth, cellSpacing);

                int position = adapterPosition;
                int drawPosition = 0;
                final List<View> views = getViews(cell);
                for (final View view : views) {
                    adapterViewHandler.addViewInAdapterView(view, drawPosition++, view.getLayoutParams());
                    mPositions.put(view, position++);
                }
                mCells.add(0, cell);
                mStartCellPosition = cellPosition;
            } else {
                final List<View> views = getViews(cell);
                for (final View view : views) {
                    mAdapterViewManager.recycle(view);
                }
            }
            cellPosition = decrementCellPosition(cellPosition);
        }
        mOffset = currentOffset;
    }

    protected abstract int getLastAdapterPositionInCell(final int cellPosition);

    private boolean isPositionBeingDrawn(final int position) {
        for (final Integer mappedPosition : mPositions.values()) {
            if (position == mappedPosition) return true;
        }

        return false;
    }

    private int incrementCellPosition(int endCellPosition) {
        if (mAdapterViewManager.isEmpty()) {
            return endCellPosition;
        }

        endCellPosition++;

        final boolean isCircularScroll = mLayoutManagerAttributes.isCircularScroll();

        final int cellCount = getCellCount();
        if (endCellPosition >= cellCount && isCircularScroll) {
            return 0;
        }

        if (!isCircularScroll) {
            endCellPosition = Math.min(cellCount, endCellPosition);
        }

        return endCellPosition;
    }

    private void incrementStartCellPosition() {
        if (mAdapterViewManager.isEmpty()) {
            return;
        }

        mStartCellPosition++;

        final int cellCount = getCellCount();
        if (mStartCellPosition >= cellCount || mStartCellPosition < 0) {
            mStartCellPosition = 0;
        }
    }

    private int decrementCellPosition(int position) {
        if (mAdapterViewManager.isEmpty()) {
            return position;
        }

        position--;

        final int cellCount = getCellCount();
        final boolean isCircularScroll = mLayoutManagerAttributes.isCircularScroll();
        if (position < 0 && isCircularScroll) {
            position = cellCount - 1;
        }

        if (!isCircularScroll) {
            position = Math.max(-1, position);
        }
        return position;
    }


    protected boolean isViewSelected(final int position) {
        final boolean isSelected = position == mSelectedPositionManager.getSelectedPosition();
        return isSelected;
    }

    public int getPosition(final View view) {
        if (mPositions.containsKey(view)) return mPositions.get(view);
        return INVALID_POSITION;
    }

    public int getViewPagerScrollDistance(final Move move) {
        switch (move) {
            case forward:
                return -mViewPageDistance - mAnimationDisplacement;
            case back:
            case none:
            default:
                return mViewPageDistance - mAnimationDisplacement;
        }
    }

    public int snapTo(final ViewGroup viewGroup) {
        final boolean isSnapToPosition = mLayoutManagerAttributes.isSnapToPosition();
        if (!isSnapToPosition) return 0;

        final int size = mScrollDirectionManager.getViewGroupSize(viewGroup);

        final View nearestView = getSnapDistanceToNearestView(size);
        if (nearestView == null) return 0;

        final boolean selectOnSnap = mLayoutManagerAttributes.selectOnSnap();
        if (selectOnSnap) setSelected(nearestView);

        final int distance = getSnapToPixelDistance(size, nearestView);
        return distance;
    }


    @Override
    protected void onDataSetChanged() {
        if (mAdapterViewManager.isEmpty()) {
            mStartCellPosition = 0;
            mOffset = 0;
            mSelectedPositionManager.setSelectNothing();

            final AdapterViewHandler adapterViewHandler = (AdapterViewHandler) mViewGroup;
            recycleCells(adapterViewHandler);

            return;
        }

        final int adapterCount = mAdapterViewManager.getAdapterCount();
        final int lastItemIndex = adapterCount - 1;

        final int size = mScrollDirectionManager.getViewGroupSize(mViewGroup);
        final View nearestViewToSnapPosition = getNearestViewToSnapPosition(size);
        final int positionOfNearestView = getPosition(nearestViewToSnapPosition);

        if (positionOfNearestView == INVALID_POSITION) return;

        int incomingPosition = positionOfNearestView;
        if (positionOfNearestView > lastItemIndex) incomingPosition = lastItemIndex;

        if (incomingPosition != INVALID_POSITION) {
            final AdapterViewHandler adapterViewHandler = (AdapterViewHandler) mViewGroup;
            jumpToPosition(adapterViewHandler, incomingPosition);
        }

        final int currentlySelectedPosition = mSelectedPositionManager.getSelectedPosition();
        if (currentlySelectedPosition > lastItemIndex)
            mSelectedPositionManager.setSelectedPosition(lastItemIndex);
    }

    public View getView(final int position) {
        final boolean isPositionBeingDrawn = isPositionBeingDrawn(position);
        if (isPositionBeingDrawn) return getDrawnView(position);

        final int maxMeasureWidth = getMaxMeasureWidth(position);
        final int maxMeasureHeight = getMaxMeasureHeight(position);

        return mAdapterViewManager.getView(mViewGroup, position, maxMeasureWidth, maxMeasureHeight);
    }

    private View getDrawnView(final int position) {
        for (final View view : mPositions.keySet()) {
            final int drawnPosition = mPositions.get(view);
            if (drawnPosition == position) return view;
        }
        return null;
    }

    public void setSelected(final int position, final AdapterViewHandler adapterViewHandler) {
        final boolean isViewOnScreen = mPositions.containsValue(position);
        final boolean isSnapToPosition = mLayoutManagerAttributes.isSnapToPosition();
        if (!isSnapToPosition && !isViewOnScreen) {
            return;
        }

        mSelectedPositionManager.setSelectedPosition(position);

        final boolean snapToPosition = mLayoutManagerAttributes.isSnapToPosition();
        if (!snapToPosition) return;

        jumpToPosition(adapterViewHandler, position);
    }

    public boolean setSelected(final View view) {
        final int position = getPosition(view);
        return mSelectedPositionManager.setSelectedPosition(position);
    }

    private void jumpToPosition(final AdapterViewHandler adapterViewHandler, final int position) {
        final int size = mScrollDirectionManager.getViewGroupSize(mViewGroup);

        final View nearestViewToSnapPosition = getNearestViewToSnapPosition(size);
        final int positionOfNearestView = getPosition(nearestViewToSnapPosition);

        if (positionOfNearestView != INVALID_POSITION) {
            final int incomingPosition = position;
            final int outgoingPosition = positionOfNearestView;

            final View outgoingView = getDrawnView(outgoingPosition);
            final View incomingView = getView(incomingPosition);
            final int cellSpacing = mLayoutManagerAttributes.getCellSpacing();

            mOffset = mSnapPositionInterface.getRedrawOffset(mScrollDirectionManager, incomingView, outgoingView, cellSpacing);

            mStartCellPosition = getCellPosition(incomingPosition);

            //TODO - FIX THIS BUG!!!! this should potentially be outside of the if statement
            recycleCells(adapterViewHandler);
        }

    }

    private void recycleCells(final AdapterViewHandler adapterViewHandler) {
        for (final Cell cell : mCells) {
            final List<View> views = getViews(cell);
            for (final View view : views) {
                adapterViewHandler.removeViewInAdapterView(view);
                mAdapterViewManager.recycle(view);
            }
        }
        mCells.clear();
        mPositions.clear();
    }

    protected Move getMove(final int displacement) {
        if (displacement < 0) {
            return Move.back;
        }

        if (displacement > 0) {
            return Move.forward;
        }

        return Move.none;
    }

    public void setAnimationStoppedListener(final AnimationStoppedListener animationStoppedListener) {
        mAnimationStoppedListener = animationStoppedListener;
    }

    protected void onAnimationStopped() {
        if (mAnimationStoppedListener != null) mAnimationStoppedListener.onAnimationStopped();
    }

    public boolean isSnapToPosition() {
        return mLayoutManagerAttributes.isSnapToPosition();
    }

    public int getViewGroupSize(final ViewGroup viewGroup) {
        return mScrollDirectionManager.getViewGroupSize(viewGroup);
    }

    public boolean isVerticalScroll() {
        return mScrollDirectionManager.isVerticalScroll();
    }

    public Parcelable onSaveInstanceState(final Parcelable parcelable) {
        final LayoutManagerState<Cell> layoutManagerState = new LayoutManagerState<Cell>(parcelable, mOffset, mStartCellPosition);
        return layoutManagerState;
    }


    public void onRestoreInstanceState(final Parcelable parcelable) {
        if (!(parcelable instanceof LayoutManagerState)){
            return;
        }

        final LayoutManagerState<Cell> layoutManagerState = (LayoutManagerState<Cell>) parcelable;
        mOffset = layoutManagerState.getOffset();
        mStartCellPosition = layoutManagerState.getStartCellPosition();
    }

    public void onItemClick(final View view, final int position, final long id) {
        if (mPressedView != null){
            mPressedView.setPressed(false);
        }
        mPressedView = view;
        mPressedView.setPressed(true);

        mViewGroup.postDelayed(new Runnable() {
            @Override
            public void run() {
                view.setPressed(false);
                mPressedView = null;
            }
        }, ViewConfiguration.getPressedStateDuration());
    }
}