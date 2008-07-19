package org.carrot2.clustering.lingo;

import java.util.Collections;
import java.util.List;

import org.carrot2.core.*;
import org.carrot2.core.attribute.*;
import org.carrot2.text.linguistic.LanguageModelFactory;
import org.carrot2.text.linguistic.SnowballLanguageModelFactory;
import org.carrot2.text.preprocessing.*;
import org.carrot2.util.attribute.*;
import org.carrot2.util.attribute.constraint.ImplementingClasses;

import bak.pcj.IntIterator;
import bak.pcj.set.IntBitSet;

import com.google.common.collect.Lists;

/**
 * Lingo clustering algorithm.
 */
@Bindable
public class LingoClusteringAlgorithm extends ProcessingComponentBase implements
    ClusteringAlgorithm
{
    /**
     * {@link Document}s to cluster.
     */
    @Processing
    @Input
    @Required
    @Internal
    @Attribute(key = AttributeNames.DOCUMENTS)
    public List<Document> documents;

    /**
     * {@link Cluster}s created by the algorithm.
     */
    @Processing
    @Output
    @Attribute(key = AttributeNames.CLUSTERS)
    public List<Cluster> clusters = null;

    /**
     * Term weighting.
     * 
     * @level Medium
     * @group Matrix model
     */
    @Input
    @Processing
    @Attribute
    @ImplementingClasses(classes =
    {
        LogTfIdfTermWeighting.class, LinearTfIdfTermWeighting.class,
        TfTermWeighting.class
    })
    public TermWeighting termWeighting = new LogTfIdfTermWeighting();

    /**
     * Tokenizer used by the algorithm, contains bindable attributes.
     */
    public Tokenizer tokenizer = new Tokenizer();

    /**
     * Case normalizer used by the algorithm, contains bindable attributes.
     */
    public CaseNormalizer caseNormalizer = new CaseNormalizer();

    /**
     * Stemmer used by the algorithm, contains bindable attributes.
     */
    public LanguageModelStemmer languageModelStemmer = new LanguageModelStemmer();

    /**
     * Stop list marker used by the algorithm, contains bindable attributes.
     */
    public StopListMarker stopListMarker = new StopListMarker();

    /**
     * Phrase extractor used by the algorithm, contains bindable attributes.
     */
    public PhraseExtractor phraseExtractor = new PhraseExtractor();

    /**
     * Label filter processor used by the algorithm, contains bindable attributes.
     */
    public LabelFilterProcessor labelFilterProcessor = new LabelFilterProcessor();

    /**
     * Language model factory used by the algorithm, contains bindable attributes.
     */
    public LanguageModelFactory languageModelFactory = new SnowballLanguageModelFactory();

    /**
     * Term-document matrix builder for the algorithm, contains bindable attributes.
     */
    public TermDocumentMatrixBuilder matrixBuilder = new TermDocumentMatrixBuilder();

    /**
     * Term-document matrix reducer for the algorithm, contains bindable attributes.
     */
    public TermDocumentMatrixReducer matrixReducer = new TermDocumentMatrixReducer();

    /**
     * Cluster label builder, contains bindable attributes.
     */
    public ClusterBuilder clusterBuilder = new ClusterBuilder();

    /**
     * Performs Lingo clustering of {@link #documents}.
     */
    @Override
    public void process() throws ProcessingException
    {
        // Preprocessing of documents
        final PreprocessingContext context = new PreprocessingContext(
            languageModelFactory.getCurrentLanguage(), documents);
        tokenizer.tokenize(context);
        caseNormalizer.normalize(context);
        languageModelStemmer.stem(context);
        stopListMarker.mark(context);
        phraseExtractor.extractPhrases(context);
        labelFilterProcessor.process(context);

        // Term-document matrix building and reduction
        LingoProcessingContext lingoContext = new LingoProcessingContext(context);
        matrixBuilder.build(lingoContext, termWeighting);
        matrixReducer.reduce(lingoContext);

        // Cluster label building
        clusterBuilder.buildLabels(lingoContext, termWeighting);

        // Document assignment
        clusterBuilder.assignDocuments(lingoContext, termWeighting);

        // Cluster merging
        clusterBuilder.merge(lingoContext);
        
        // Temporary cluster rendering
        clusters = Lists.newArrayList();

        final int [] clusterLabelIndex = lingoContext.clusterLabelFeatureIndex;
        final IntBitSet [] clusterDocuments = lingoContext.clusterDocuments;

        final int [][] phrasesWordIndices = context.allPhrases.wordIndices;
        final char [][] wordsImage = context.allWords.image;
        final int wordCount = wordsImage.length;

        for (int i = 0; i < clusterLabelIndex.length; i++)
        {
            final Cluster cluster = new Cluster();
            final StringBuilder label = new StringBuilder();

            final int labelFeature = clusterLabelIndex[i];
            if (labelFeature < 0)
            {
                // Cluster removed during merging
                continue;
            }
            
            if (labelFeature < wordCount)
            {
                label.append(wordsImage[labelFeature]);
            }
            else
            {
                final int phraseIndex = labelFeature - wordCount;
                for (int wordIndex = 0; wordIndex < phrasesWordIndices[phraseIndex].length; wordIndex++)
                {
                    label.append(wordsImage[phrasesWordIndices[phraseIndex][wordIndex]]);
                    if (wordIndex < phrasesWordIndices[phraseIndex].length - 1)
                    {
                        label.append(" ");
                    }

                }
            }
            cluster.addPhrases(label.toString());

            // Add documents
            for (IntIterator it = clusterDocuments[i].iterator(); it.hasNext();)
            {
                cluster.addDocuments(documents.get(it.next()));
            }

            // Add cluster
            if (cluster.getDocuments().size() > 1)
            {
                clusters.add(cluster);
            }
        }

        Collections.sort(clusters, Cluster.BY_REVERSED_SIZE_AND_LABEL_COMPARATOR);
        Cluster.appendOtherTopics(documents, clusters);
    }
}
