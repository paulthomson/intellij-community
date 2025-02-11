// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.actions.diff.lst;

import com.intellij.diff.DiffContext;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.tools.fragmented.*;
import com.intellij.diff.util.DiffGutterOperation;
import com.intellij.diff.util.DiffGutterRenderer;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diff.DiffBundle;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.vcs.changes.actions.diff.lst.LocalTrackerDiffUtil.LineFragmentData;
import com.intellij.openapi.vcs.changes.actions.diff.lst.LocalTrackerDiffUtil.SelectedTrackerLine;
import com.intellij.openapi.vcs.changes.actions.diff.lst.LocalTrackerDiffUtil.ToggleableLineRange;
import com.intellij.openapi.vcs.ex.Range;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

public class UnifiedLocalChangeListDiffViewer extends UnifiedDiffViewer {
  @NotNull private final LocalChangeListDiffRequest myLocalRequest;

  private final boolean myAllowExcludeChangesFromCommit;

  private final LocalTrackerDiffUtil.LocalTrackerActionProvider myTrackerActionProvider;
  private final LocalTrackerDiffUtil.ExcludeAllCheckboxPanel myExcludeAllCheckboxPanel;

  private final @NotNull List<RangeHighlighter> myToggleExclusionsHighlighters = new ArrayList<>();

  public UnifiedLocalChangeListDiffViewer(@NotNull DiffContext context,
                                          @NotNull LocalChangeListDiffRequest localRequest) {
    super(context, localRequest.getRequest());
    myLocalRequest = localRequest;

    myAllowExcludeChangesFromCommit = DiffUtil.isUserDataFlagSet(LocalChangeListDiffTool.ALLOW_EXCLUDE_FROM_COMMIT, context);
    myTrackerActionProvider = new MyLocalTrackerActionProvider(this, localRequest, myAllowExcludeChangesFromCommit);
    myExcludeAllCheckboxPanel = new LocalTrackerDiffUtil.ExcludeAllCheckboxPanel(this, getEditor());
    myExcludeAllCheckboxPanel.init(myLocalRequest, myAllowExcludeChangesFromCommit);

    LocalTrackerDiffUtil.installTrackerListener(this, myLocalRequest);
  }

  @Nullable
  @Override
  protected JComponent createTitles() {
    JComponent titles = super.createTitles();

    BorderLayoutPanel titleWithCheckbox = JBUI.Panels.simplePanel();
    if (titles != null) titleWithCheckbox.addToCenter(titles);
    titleWithCheckbox.addToLeft(myExcludeAllCheckboxPanel);
    return titleWithCheckbox;
  }

  @NotNull
  @Override
  protected List<AnAction> createEditorPopupActions() {
    List<AnAction> group = new ArrayList<>(super.createEditorPopupActions());
    group.addAll(LocalTrackerDiffUtil.createTrackerActions(myTrackerActionProvider));
    return group;
  }

  @NotNull
  @Override
  protected UnifiedDiffChangeUi createUi(@NotNull UnifiedDiffChange change) {
    if (change instanceof MyUnifiedDiffChange) return new MyUnifiedDiffChangeUi(this, (MyUnifiedDiffChange)change);
    return super.createUi(change);
  }

  @NotNull
  private Runnable superComputeDifferences(@NotNull ProgressIndicator indicator) {
    return super.computeDifferences(indicator);
  }

  @NotNull
  @Override
  protected Runnable computeDifferences(@NotNull ProgressIndicator indicator) {
    Document document1 = getContent1().getDocument();
    Document document2 = getContent2().getDocument();

    return LocalTrackerDiffUtil.computeDifferences(
      myLocalRequest.getLineStatusTracker(),
      document1,
      document2,
      myLocalRequest.getChangelistId(),
      myTextDiffProvider,
      indicator,
      new MyLocalTrackerDiffHandler(document1, document2, indicator));
  }

  private final class MyLocalTrackerDiffHandler implements LocalTrackerDiffUtil.LocalTrackerDiffHandler {
    @NotNull private final Document myDocument1;
    @NotNull private final Document myDocument2;
    @NotNull private final ProgressIndicator myIndicator;

    private MyLocalTrackerDiffHandler(@NotNull Document document1,
                                      @NotNull Document document2,
                                      @NotNull ProgressIndicator indicator) {
      myDocument1 = document1;
      myDocument2 = document2;
      myIndicator = indicator;
    }

    @NotNull
    @Override
    public Runnable done(boolean isContentsEqual,
                         CharSequence @NotNull [] texts,
                         @NotNull List<ToggleableLineRange> toggleableLineRanges) {
      @NotNull List<LineFragment> fragments = new ArrayList<>();
      @NotNull List<LineFragmentData> fragmentsData = new ArrayList<>();

      for (ToggleableLineRange range : toggleableLineRanges) {
        List<LineFragment> rangeFragments = range.getFragments();
        fragments.addAll(rangeFragments);
        fragmentsData.addAll(Collections.nCopies(rangeFragments.size(), range.getFragmentData()));
      }

      UnifiedFragmentBuilder builder = ReadAction.compute(() -> {
        myIndicator.checkCanceled();
        return new MyUnifiedFragmentBuilder(fragments, fragmentsData, myDocument1, myDocument2).exec();
      });

      Runnable applyChanges = apply(builder, texts, myIndicator);
      Runnable applyGutterExcludeOperations = applyGutterOperations(builder, toggleableLineRanges);

      return () -> {
        applyChanges.run();
        applyGutterExcludeOperations.run();
      };
    }

    @NotNull
    @Override
    public Runnable retryLater() {
      ApplicationManager.getApplication().invokeLater(() -> scheduleRediff());
      throw new ProcessCanceledException();
    }

    @NotNull
    @Override
    public Runnable fallback() {
      return superComputeDifferences(myIndicator);
    }

    @NotNull
    @Override
    public Runnable fallbackWithProgress() {
      Runnable callback = superComputeDifferences(myIndicator);
      return () -> {
        callback.run();
        getStatusPanel().setBusy(true);
      };
    }

    @NotNull
    @Override
    public Runnable error() {
      return applyErrorNotification();
    }
  }

  private class MyUnifiedFragmentBuilder extends UnifiedFragmentBuilder {
    @NotNull private final List<LineFragmentData> myFragmentsData;

    MyUnifiedFragmentBuilder(@NotNull List<? extends LineFragment> fragments,
                             @NotNull List<LineFragmentData> fragmentsData,
                             @NotNull Document document1,
                             @NotNull Document document2) {
      super(fragments, document1, document2, myMasterSide);
      myFragmentsData = fragmentsData;
    }

    @NotNull
    @Override
    protected UnifiedDiffChange createDiffChange(int blockStart,
                                                 int insertedStart,
                                                 int blockEnd,
                                                 int fragmentIndex) {
      LineFragment fragment = myFragments.get(fragmentIndex);
      LineFragmentData data = myFragmentsData.get(fragmentIndex);
      boolean isSkipped = data.isSkipped();
      boolean isExcluded = data.isExcluded(myAllowExcludeChangesFromCommit);
      return new MyUnifiedDiffChange(blockStart, insertedStart, blockEnd, fragment, isExcluded, isSkipped,
                                     data.getChangelistId(), data.isFromActiveChangelist(),
                                     data.isExcludedFromCommit(), data.isPartiallyExcluded());
    }
  }

  @Override
  protected void onAfterRediff() {
    super.onAfterRediff();
    myExcludeAllCheckboxPanel.refresh();
  }

  @Override
  protected void clearDiffPresentation() {
    super.clearDiffPresentation();

    for (RangeHighlighter operation : myToggleExclusionsHighlighters) {
      operation.dispose();
    }
    myToggleExclusionsHighlighters.clear();
  }

  private @NotNull Runnable applyGutterOperations(@NotNull UnifiedFragmentBuilder builder,
                                                  @NotNull List<ToggleableLineRange> toggleableLineRanges) {
    if (!myAllowExcludeChangesFromCommit) return EmptyRunnable.INSTANCE;

    return () -> {
      for (ToggleableLineRange toggleableLineRange : toggleableLineRanges) {
        myToggleExclusionsHighlighters.addAll(createGutterToggleRenderers(builder, toggleableLineRange));
      }
      getEditor().getGutterComponentEx().revalidateMarkup();
    };
  }

  private @NotNull List<RangeHighlighter> createGutterToggleRenderers(@NotNull UnifiedFragmentBuilder builder,
                                                                      @NotNull ToggleableLineRange toggleableLineRange) {
    LineFragmentData fragmentData = toggleableLineRange.getFragmentData();
    if (!fragmentData.isFromActiveChangelist()) return Collections.emptyList();

    List<RangeHighlighter> result = new ArrayList<>();
    result.add(createCheckboxToggleHighlighter(builder, toggleableLineRange));
    if (LocalTrackerDiffUtil.shouldShowToggleAreaThumb(toggleableLineRange)) {
      result.add(createToggleAreaThumb(builder, toggleableLineRange));
    }
    return result;
  }

  @NotNull
  private RangeHighlighter createCheckboxToggleHighlighter(@NotNull UnifiedFragmentBuilder builder,
                                                           @NotNull ToggleableLineRange toggleableLineRange) {
    Range lineRange = toggleableLineRange.getLineRange();
    LineFragmentData fragmentData = toggleableLineRange.getFragmentData();
    LineFragment firstFragment = ContainerUtil.getFirstItem(toggleableLineRange.getFragments());

    Side side = ObjectUtils.chooseNotNull(fragmentData.getPartialExclusionSide(), getMasterSide());
    EditorEx editor = getEditor();
    int line = firstFragment != null ? side.getStartLine(firstFragment)
                                     : side.select(lineRange.getVcsLine1(), lineRange.getLine1());
    LineNumberConvertor lineConvertor = side.select(builder.getConvertor1(), builder.getConvertor2());
    int editorLine = lineConvertor.convertApproximateInv(line);
    int offset = DiffGutterOperation.lineToOffset(editor, editorLine);

    Icon icon = fragmentData.isExcludedFromCommit() ? AllIcons.Diff.GutterCheckBox : AllIcons.Diff.GutterCheckBoxSelected;
    RangeHighlighter checkboxHighlighter = editor.getMarkupModel().addRangeHighlighter(null, offset, offset,
                                                                                       HighlighterLayer.ADDITIONAL_SYNTAX,
                                                                                       HighlighterTargetArea.LINES_IN_RANGE);
    checkboxHighlighter.setGutterIconRenderer(
      new DiffGutterRenderer(icon, DiffBundle.message("action.presentation.diff.include.into.commit.text")) {
        @Override
        protected void handleMouseClick() {
          if (!isContentGood()) return;

          LocalTrackerDiffUtil.toggleRangeAtLine(myTrackerActionProvider, line, fragmentData);
        }
      });

    return checkboxHighlighter;
  }

  @NotNull
  private RangeHighlighter createToggleAreaThumb(@NotNull UnifiedFragmentBuilder builder,
                                                 @NotNull ToggleableLineRange toggleableLineRange) {
    Range lineRange = toggleableLineRange.getLineRange();
    int line1 = builder.getConvertor1().convertApproximateInv(lineRange.getVcsLine1());
    int line2 = builder.getConvertor2().convertApproximateInv(lineRange.getLine2());
    return LocalTrackerDiffUtil.createToggleAreaThumb(getEditor(), line1, line2);
  }

  private static final class MyUnifiedDiffChange extends UnifiedDiffChange {
    @NotNull private final String myChangelistId;
    private final boolean myIsFromActiveChangelist;
    private final boolean myIsExcludedFromCommit;
    private final boolean myIsPartiallyExcluded;

    private MyUnifiedDiffChange(int blockStart,
                                int insertedStart,
                                int blockEnd,
                                @NotNull LineFragment lineFragment,
                                boolean isExcluded,
                                boolean isSkipped,
                                @NotNull String changelistId,
                                boolean isFromActiveChangelist,
                                boolean isExcludedFromCommit,
                                boolean isPartiallyExcluded) {
      super(blockStart, insertedStart, blockEnd, lineFragment, isExcluded, isSkipped);
      myChangelistId = changelistId;
      myIsFromActiveChangelist = isFromActiveChangelist;
      myIsExcludedFromCommit = isExcludedFromCommit;
      myIsPartiallyExcluded = isPartiallyExcluded;
    }

    @NotNull
    public String getChangelistId() {
      return myChangelistId;
    }

    public boolean isFromActiveChangelist() {
      return myIsFromActiveChangelist;
    }

    public boolean isExcludedFromCommit() {
      return myIsExcludedFromCommit;
    }

    private boolean isPartiallyExcluded() {
      return myIsPartiallyExcluded;
    }
  }

  private static final class MyUnifiedDiffChangeUi extends UnifiedDiffChangeUi {
    private MyUnifiedDiffChangeUi(@NotNull UnifiedLocalChangeListDiffViewer viewer,
                                  @NotNull MyUnifiedDiffChange change) {
      super(viewer, change);
    }

    @NotNull
    private UnifiedLocalChangeListDiffViewer getViewer() {
      return (UnifiedLocalChangeListDiffViewer)myViewer;
    }

    @NotNull
    private MyUnifiedDiffChange getChange() {
      return ((MyUnifiedDiffChange)myChange);
    }

    @Override
    protected void doInstallActionHighlighters() {
      if (getChange().isPartiallyExcluded()) return; // do not draw multiple ">>"
      super.doInstallActionHighlighters();
    }
  }

  private static final class MyLocalTrackerActionProvider extends LocalTrackerDiffUtil.LocalTrackerActionProvider {
    @NotNull private final UnifiedLocalChangeListDiffViewer myViewer;

    private MyLocalTrackerActionProvider(@NotNull UnifiedLocalChangeListDiffViewer viewer,
                                         @NotNull LocalChangeListDiffRequest localRequest,
                                         boolean allowExcludeChangesFromCommit) {
      super(viewer, localRequest, allowExcludeChangesFromCommit);
      myViewer = viewer;
    }

    @Nullable
    @Override
    public List<LocalTrackerDiffUtil.LocalTrackerChange> getSelectedTrackerChanges(@NotNull AnActionEvent e) {
      if (!myViewer.isContentGood()) return null;

      return StreamEx.of(myViewer.getSelectedChanges())
        .select(MyUnifiedDiffChange.class)
        .map(it -> new LocalTrackerDiffUtil.LocalTrackerChange(myViewer.transferLineFromOneside(Side.RIGHT, it.getLine1()),
                                                               myViewer.transferLineFromOneside(Side.RIGHT, it.getLine2()),
                                                               it.getChangelistId(),
                                                               it.isExcludedFromCommit()))
        .toList();
    }

    @Override
    public @Nullable SelectedTrackerLine getSelectedTrackerLines(@NotNull AnActionEvent e) {
      if (!myViewer.isContentGood()) return null;

      BitSet deletions = new BitSet();
      BitSet additions = new BitSet();
      DiffUtil.getSelectedLines(myViewer.getEditor()).stream().forEach(line -> {
        int line1 = myViewer.transferLineFromOnesideStrict(Side.LEFT, line);
        if (line1 != -1) deletions.set(line1);
        int line2 = myViewer.transferLineFromOnesideStrict(Side.RIGHT, line);
        if (line2 != -1) additions.set(line2);
      });

      return new SelectedTrackerLine(deletions, additions);
    }
  }
}
