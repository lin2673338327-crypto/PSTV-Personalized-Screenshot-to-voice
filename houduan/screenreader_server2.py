import os
import base64
import requests
from flask import Flask, request, send_file, jsonify
from pydantic import BaseModel
from openai import OpenAI
from PIL import Image
import io

app = Flask(__name__)

# OpenAI client for text extraction
client = OpenAI(
    # Please replace with your own OpenAI-compatible API base URL
    base_url='https://api.openai.com/v1',  # Default official endpoint (Example)
    # Please input your own valid OpenAI API key here
    api_key="YOUR_OPENAI_API_KEY_HERE"
)

# Pydantic model for parsed response
class Maintext(BaseModel):
    main_text: str

# Function to encode an image as base64
def encode_image(image_path):
    with open(image_path, "rb") as img_file:
        return base64.b64encode(img_file.read()).decode('utf-8')

# Function to extract text from multiple images
def extract_text_from_images(image_paths):
    try:
        messages = [
            {
                "role": "system",
                "content": "You are an expert on reading screenshots of a smartphone screen. Extract the main text as completely as possible."
            },
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "I have some screenshots. Extract the text from them."}
                ]
            }
        ]
        
        # Encode images and add to messages
        for image_path in image_paths:
            encoded_image = encode_image(image_path)
            messages[1]["content"].append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{encoded_image}"}})
        
        completion = client.beta.chat.completions.parse(
            model="gpt-4o-2024-08-06",
            messages=messages,
            response_format=Maintext
        )
        
        return completion.choices[0].message.parsed.main_text
    
    except Exception as e:
        print(f"Error extracting text: {e}")
        return None

# Function to generate speech using GPT-SoVITS
def tts_infer_with_gpt_sovits(text):
    output_path = "./output.wav"
    # Please replace with your own TTS service endpoint
    tts_url = "http://your-tts-server-address:port/tts"

    tts_params = {
        "text": text,
        "text_lang": "zh",
        # Please replace with the actual path to your reference audio file
        "ref_audio_path": "YOUR_REFERENCE_AUDIO_FILE_PATH",
        "prompt_lang": "zh",
        # Please replace the prompt text to match your reference audio
        "prompt_text": "This is a sample prompt text for voice cloning.",
        "text_split_method": "cut0",
        "batch_size": 1,
        "parallel_infer": "true",
        "media_type": "wav"
    }

    try:
        tts_response = requests.get(tts_url, params=tts_params, stream=True)
        tts_response.raise_for_status()

        if tts_response.headers.get('Content-Type') == 'audio/wav':
            with open(output_path, "wb") as f:
                for chunk in tts_response.iter_content(chunk_size=8192):
                    f.write(chunk)
            return output_path
        else:
            return f"TTS error: {tts_response.text}"
    
    except requests.exceptions.RequestException as e:
        return f"TTS request error: {e}"

@app.route('/download_audio', methods=['GET'])
def download_audio():
    """ Endpoint for clients to download the generated audio file. """
    audio_path = "./output.wav"  # Ensure the path is correct
    if os.path.exists(audio_path):
        return send_file(audio_path, mimetype="audio/wav", as_attachment=True)
    else:
        return jsonify({"error": "Audio file not found"}), 404
    
@app.route('/synthesize', methods=['POST'])
def synthesize():
    """
    API endpoint that receives images, extracts text, and generates speech.
    """
    if 'images' not in request.files:
        return jsonify({"error": "No images uploaded"}), 400

    images = request.files.getlist("images")
    image_paths = []

    # Save uploaded images temporarily
    for image in images:
        temp_path = f"./temp_{image.filename}"
        image.save(temp_path)
        image_paths.append(temp_path)

    # Extract text from images
    extracted_text = extract_text_from_images(image_paths)

    # Remove temporary files
    for path in image_paths:
        os.remove(path)

    if not extracted_text:
        return jsonify({"error": "Text extraction failed"}), 500

    # Generate speech from extracted text
    audio_path = tts_infer_with_gpt_sovits(extracted_text)

    if isinstance(audio_path, str) and audio_path.endswith(".wav"):
        return send_file(audio_path, mimetype="audio/wav", as_attachment=True)
    else:
        return jsonify({"error": audio_path}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=9980, debug=True)