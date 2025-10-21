import mmcorej.CMMCore;

public class MMHelloWorld {
    public static void main(String[] args) {
        try {
            CMMCore core = new CMMCore();
            core.loadSystemConfiguration("config/MMConfig_Demo.cfg");

            System.out.println("Loaded devices:");
            for (String dev : core.getLoadedDevices()) {
                System.out.println(" - " + dev);
            }

            core.snapImage();
            Object img = core.getImage();

            System.out.println("Image snapped: " + core.getImageWidth() + " x " + core.getImageHeight());
            System.out.println("Image type: " + img.getClass().getSimpleName());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
