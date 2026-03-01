import os
import requests
import threading
from kivy.lang import Builder
from kivy.uix.screenmanager import ScreenManager, Screen
from kivymd.app import MDApp
from kivy.core.audio import SoundLoader
from plyer import filechooser
from kivymd.toast import toast
from kivy.clock import Clock


KV = """
ScreenManager:
    MainScreen:

<MainScreen>:
    name: "main"
    BoxLayout:
        orientation: "vertical"
        spacing: dp(10)
        padding: dp(20)

        MDLabel:
            text: "Select images and generate voice"
            halign: "center"
            theme_text_color: "Primary"

        MDRaisedButton:
            text: "Select Images"
            on_release: app.select_images()

        MDLabel:
            id: selected_images
            text: "No images selected"
            halign: "center"
            theme_text_color: "Secondary"

        MDRaisedButton:
            text: "Send to Server"
            on_release: app.send_images()

        MDProgressBar:
            id: progress_bar
            value: 0
            max: 100

        MDRaisedButton:
            text: "Play Audio"
            on_release: app.play_audio()
"""

class MainScreen(Screen):
    pass

class ImageToSpeechApp(MDApp):
    def build(self):
        return Builder.load_string(KV)

    def select_images(self):
        """ Opens file chooser to select images """
        filechooser.open_file(on_selection=self.set_selected_images, multiple=True)

    def set_selected_images(self, selection):
        """ Sets the selected image paths """
        if selection:
            self.image_paths = selection
            self.root.get_screen("main").ids.selected_images.text = f"{len(selection)} images selected"
        else:
            self.root.get_screen("main").ids.selected_images.text = "No images selected"

    def send_images(self):
        """ Sends selected images to the Flask server """
        if not hasattr(self, 'image_paths') or not self.image_paths:
            toast("Please select images first!")
            return
        
        threading.Thread(target=self.upload_images, daemon=True).start()


    def upload_images(self):
        """ Uploads images to the Flask server and retrieves the audio file """
        url = "http:"  # Replace with your actual server IP
        files = [("images", (os.path.basename(img), open(img, "rb"), "image/jpeg")) for img in self.image_paths]

        self.root.get_screen("main").ids.progress_bar.value = 20
        try:
            response = requests.post(url, files=files)
            self.root.get_screen("main").ids.progress_bar.value = 50

            if response.status_code == 200:
                with open("output.wav", "wb") as f:
                    f.write(response.content)
                self.root.get_screen("main").ids.progress_bar.value = 100
                Clock.schedule_once(lambda dt: toast("Audio received and saved!"), 0)  # Fix UI threading issue
            else:
                Clock.schedule_once(lambda dt: toast(f"Server error: {response.status_code}"), 0)
        except Exception as e:
            Clock.schedule_once(lambda dt: toast(f"Error: {e}"), 0)  # Fix UI threading issue

    def play_audio(self):
        """ Plays the received audio file """
        if os.path.exists("output.wav"):
            sound = SoundLoader.load("output.wav")
            if sound:
                sound.play()
                toast("Playing audio...")
            else:
                toast("Error loading audio file")
        else:
            toast("No audio file found!")

if __name__ == "__main__":
    ImageToSpeechApp().run()
