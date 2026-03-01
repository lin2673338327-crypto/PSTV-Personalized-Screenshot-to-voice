import requests

url = "http://localhost:9980/synthesize"
files = [
    ('images', open('screenshot1.jpg', 'rb')),
    ('images', open('screenshot2.jpg', 'rb'))
]

response = requests.post(url, files=files)

if response.status_code == 200:
    with open("output.wav", "wb") as f:
        f.write(response.content)
    print("Audio saved as output.wav")
else:
    print("Error:", response.json())