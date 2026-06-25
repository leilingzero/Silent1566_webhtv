package com.fongmi.android.tv.ui.activity;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoActivityLayoutTest {

    private static final List<String> REQUIRED_EPISODE_IDS = Arrays.asList(
            "episodeTitleBar",
            "episodeViewMode"
    );
    private static final List<String> REQUIRED_TMDB_MOVABLE_IDS = Arrays.asList(
            "flagTitleBar",
            "flag",
            "episodeTitleBar",
            "episode"
    );
    private static final List<String> REQUIRED_FULLSCREEN_CONTROL_IDS = Arrays.asList(
            "cast",
            "keep",
            "display",
            "info"
    );

    @Test
    public void mobileActivityVideoLayoutsExposeEpisodeModeControls() throws Exception {
        List<Path> layoutFiles = Files.walk(findMobileResPath())
                .filter(path -> path.getFileName().toString().equals("activity_video.xml"))
                .filter(path -> path.getParent().getFileName().toString().startsWith("layout"))
                .collect(Collectors.toList());

        assertFalse("No mobile activity_video.xml layouts found", layoutFiles.isEmpty());
        for (Path layoutFile : layoutFiles) {
            Set<String> ids = collectAndroidIds(layoutFile.toFile());
            for (String requiredId : REQUIRED_EPISODE_IDS) {
                assertTrue(layoutFile + " is missing @+id/" + requiredId, ids.contains(requiredId));
            }
        }
    }

    @Test
    public void mobileActivityVideoLayoutsExposeTmdbMovableContainers() throws Exception {
        List<Path> layoutFiles = Files.walk(findMobileResPath())
                .filter(path -> path.getFileName().toString().equals("activity_video.xml"))
                .filter(path -> path.getParent().getFileName().toString().startsWith("layout"))
                .collect(Collectors.toList());

        assertFalse("No mobile activity_video.xml layouts found", layoutFiles.isEmpty());
        for (Path layoutFile : layoutFiles) {
            Set<String> ids = collectAndroidIds(layoutFile.toFile());
            for (String requiredId : REQUIRED_TMDB_MOVABLE_IDS) {
                assertTrue(layoutFile + " is missing @+id/" + requiredId, ids.contains(requiredId));
            }
        }
    }

    @Test
    public void mobileActivityVideoLayoutsHaveFusionChromeHost() throws Exception {
        List<Path> layoutFiles = Files.walk(findMobileResPath())
                .filter(path -> path.getFileName().toString().equals("activity_video.xml"))
                .filter(path -> path.getParent().getFileName().toString().startsWith("layout"))
                .collect(Collectors.toList());

        assertFalse("No mobile activity_video.xml layouts found", layoutFiles.isEmpty());
        for (Path layoutFile : layoutFiles) {
            Element video = findAndroidId(layoutFile.toFile(), "video");
            assertTrue(layoutFile + " is missing @+id/video", video != null);
            Node parent = video.getParentNode();
            String parentName = parent == null ? "" : parent.getNodeName();
            assertTrue(layoutFile + " must keep @+id/video inside a RelativeLayout-compatible host",
                    "RelativeLayout".equals(parentName) || "com.fongmi.android.tv.ui.custom.ProgressLayout".equals(parentName));
        }
    }

    @Test
    public void mobileVodControlLayoutExposesFullscreenTopActions() throws Exception {
        Path controlLayout = findMobileResPath().resolve(Path.of("layout", "view_control_vod.xml"));
        Set<String> ids = collectAndroidIds(controlLayout.toFile());
        for (String requiredId : REQUIRED_FULLSCREEN_CONTROL_IDS) {
            assertTrue(controlLayout + " is missing @+id/" + requiredId, ids.contains(requiredId));
        }
    }

    private static Path findMobileResPath() {
        Path moduleRelative = Path.of("src", "mobile", "res");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "mobile", "res");
    }

    private static Set<String> collectAndroidIds(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Set<String> ids = new HashSet<>();
        NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("android:id");
            int slash = id.indexOf('/');
            if (slash >= 0 && slash + 1 < id.length()) ids.add(id.substring(slash + 1));
        }
        return ids;
    }

    private static Element findAndroidId(File file, String value) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("android:id");
            if (id.endsWith("/" + value)) return element;
        }
        return null;
    }
}
