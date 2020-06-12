package io.noties.markwon.sample.processor;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import io.noties.markwon.sample.annotations.MarkwonSampleInfo;

public class MarkwonSampleProcessor extends AbstractProcessor {

    private static final String KEY_SAMPLES_FILE = "markwon.samples.file";
    private static final DateFormat ID_DATEFORMAT = new SimpleDateFormat("YYYYMMDDHHmmss", Locale.ROOT);

    private Logger logger;
    private String samplesFilePath;

    private List<MarkwonSample> samples;
    private boolean samplesUpdated;

    @Override
    public Set<String> getSupportedOptions() {
        return Collections.singleton(KEY_SAMPLES_FILE);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(MarkwonSampleInfo.class.getName());
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);

        logger = new Logger(processingEnvironment.getMessager());

        samplesFilePath = processingEnvironment.getOptions().get(KEY_SAMPLES_FILE);

        try {
            // create mutable copy
            samples = new ArrayList<>(readCurrentSamples(samplesFilePath));
        } catch (Throwable t) {
            logger.error(t.getMessage());
            throw new RuntimeException(t);
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        if (!roundEnvironment.processingOver()) {
            final long begin = System.currentTimeMillis();
            final Set<? extends Element> elements = roundEnvironment.getElementsAnnotatedWith(MarkwonSampleInfo.class);
            if (elements != null) {

                for (Element element : elements) {
                    process(element);
                }

                if (samplesUpdated) {
                    logger.info("samples updated, writing at path: %s", samplesFilePath);
                    try {
                        writeSamples(samplesFilePath, samples);
                    } catch (Throwable t) {
                        logger.error(t.getMessage());
                        throw new RuntimeException(t);
                    }
                }
            }
            final long end = System.currentTimeMillis();
            logger.info("processing took: %d ms", end - begin);
        }
        return false;
    }

    private void process(@NonNull Element element) {
        try {
            final MarkwonSample sample = parse((TypeElement) element);
            final boolean updated = updateSamples(samples, sample);
            if (updated) {
                logger.info("updated sample: '%s'", sample.javaClassName);
            }
            samplesUpdated = samplesUpdated || updated;
        } catch (Throwable t) {
            logger.error(t.getMessage());
            throw new RuntimeException(t);
        }
    }

    @NonNull
    private static List<MarkwonSample> readCurrentSamples(@NonNull String path) throws Throwable {

        final File file = new File(path);
        if (!file.exists()) {
            // the very first one, no need to create file at this point, just return empty list
            return Collections.emptyList();
        }

        try (
                InputStream inputStream = FileUtils.openInputStream(file);
                Reader reader = new InputStreamReader(inputStream)
        ) {

            return new Gson()
                    .fromJson(reader, new TypeToken<List<MarkwonSample>>() {
                    }.getType());
        } catch (IOException e) {
            throw new Throwable(e);
        }
    }

    private static void writeSamples(@NonNull String path, @NonNull List<MarkwonSample> samples) throws Throwable {

        final File file = new File(path);

        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    throw new Throwable("Cannot create new file at: " + path);
                }
            } catch (IOException e) {
                throw new Throwable("Cannot create new file at: " + path);
            }
        }

        // sort based on id (it is date)
        // new items come first (DESC order)
        Collections.sort(samples, (lhs, rhs) -> rhs.id.compareTo(lhs.id));

        final String json = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(samples);

        FileUtils.write(file, json, StandardCharsets.UTF_8);
    }

    @NonNull
    private static MarkwonSample parse(@NonNull TypeElement element) throws Throwable {
        final MarkwonSampleInfo info = element.getAnnotation(MarkwonSampleInfo.class);
        if (info == null) {
            throw new Throwable("Cannot obtain `MarkwonSampleInfo` annotation");
        }

        final String id = info.id();

        final MarkwonSample sample = new MarkwonSample(
                element.getQualifiedName().toString(),
                id,
                info.title(),
                info.description(),
                new HashSet<>(Arrays.asList(info.artifacts())),
                new HashSet<>(Arrays.asList(info.tags()))
        );

        try {
            ID_DATEFORMAT.parse(id);
        } catch (ParseException e) {
            throw new Throwable(String.format("sample: '%s', id does not match pattern: '%s'",
                    sample.javaClassName,
                    id)
            );
        }

        return sample;
    }

    // returns boolean indicating if samples were updated
    private static boolean updateSamples(@NonNull List<MarkwonSample> samples, @NonNull MarkwonSample sample) {

        final ListIterator<MarkwonSample> iterator = samples.listIterator();

        boolean found = false;
        boolean updated = false;

        while (iterator.hasNext()) {
            final MarkwonSample existing = iterator.next();

            // check for id
            if (existing.id.equals(sample.id)) {
                // if not the same -> replace
                if (!existing.equals(sample)) {
                    iterator.set(sample);
                    updated = true;
                }

                found = true;
                break;
            }
        }

        if (!found) {
            samples.add(sample);
        }

        // if not found (inserted new) or updated (found and was different)
        return !found || updated;
    }
}