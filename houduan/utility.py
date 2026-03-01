from bs4 import BeautifulSoup
from html2image import Html2Image
from pathlib import Path
import os
import base64
import json

def revise_img_src(img_src: Path, input_html_path: Path) -> Path:
    """
    Reads an HTML file, updates the src attribute of the first img tag found,
    and saves the modified HTML to a new file named after the image.

    :param img_src: The Path object for the img source.
    :param input_html_path: Path to the input HTML template.
    :return: Path to the modified HTML file.

    根据提供的参数将缺陷标签添加到HTML模板中。
：param parameters_list：包含（左、上、宽、高、标签数）的元组列表。
数值以百分比表示。
：param img_src：输入图像的路径。
：param-template_html：html模板的路径。
：return：标记的HTML文件的路径。
    """
    try:
        # Ensure the input HTML template exists
        if not input_html_path.is_file():
            raise FileNotFoundError(f"The template HTML file '{input_html_path}' does not exist.")

        # Read the original HTML content
        with input_html_path.open('r', encoding='utf-8') as file:
            soup = BeautifulSoup(file, 'html.parser')

        # Find the first img tag and update its 'src' attribute
        img_tag = soup.find('img')
        if img_tag:
            old_src = img_tag.get('src', '')
            img_tag['src'] = str(img_src.resolve())
            print(f"Updated img src from '{old_src}' to '{img_src}'.")
        else:
            raise ValueError("No img tag found in the HTML template.")

        # Define the output HTML file path
        output_html_path = img_src.parent / (img_src.stem + '.html')

        # Write the modified HTML to the output file
        with output_html_path.open('w', encoding='utf-8') as file:
            file.write(soup.prettify())

        print(f"Modified HTML saved to '{output_html_path}'.")
        return output_html_path

    except Exception as e:
        print(f"Error in revise_img_src: {e}")
        raise

def add_labels_in_image_via_html(parameters_list, img_src: Path, template_html: Path) -> Path:
    """
    Adds defect labels to the HTML template based on provided parameters.

    :param parameters_list: List of tuples containing (left, top, width, height, label_number).
                            Values are in percentages.
    :param img_src: Path to the input image.
    :param template_html: Path to the HTML template.
    :return: Path to the labeled HTML file.

    根据提供的参数将缺陷标签添加到HTML模板中。
：param parameters_list：包含（左、上、宽、高、标签数）的元组列表。
数值以百分比表示。
：param img_src：输入图像的路径。
：param-template_html：html模板的路径。
：return：标记的HTML文件的路径。
    """
    try:
        # Update the img src in the HTML and get the modified HTML path
        modified_html_path = revise_img_src(img_src, template_html)

        # Read the modified HTML content
        with modified_html_path.open('r', encoding='utf-8') as file:
            soup = BeautifulSoup(file, 'html.parser')

        # Find the .image-container div
        image_container = soup.find('div', class_='image-container')
        if not image_container:
            raise ValueError("The .image-container div was not found in the HTML template.")

        # Generate defect box elements and append them to the .image-container
        for params in parameters_list:
            left, top, width, height, label_number = params

            # Create the defect box div with dynamic styling
            defect_box = soup.new_tag('div', **{
                'class': 'defect-box',
                'style': (
                    f'left: {left}%; top: {top}%; width: {width}%; height: {height}%; '
                    'border: 3px solid green;'  # Changed border to green for consistency
                )
            })

            # Create the label inside the defect box
            box_label = soup.new_tag('div', **{
                'class': 'box-label',
                'style': 'color: green; font-weight: bold;'  # Enhanced visibility
            })
            box_label.string = f"#{label_number}"  # Set the label text
            defect_box.append(box_label)  # Add the label to the defect box

            # Append the defect box to the image-container
            image_container.append(defect_box)

            print(f"Added defect box #{label_number} at ({left}%, {top}%) with size {width}%x{height}%.")

        # Write the updated HTML to the same file
        with modified_html_path.open('w', encoding='utf-8') as file:
            file.write(str(soup.prettify()))

        print(f"Labeled HTML has been updated at '{modified_html_path}'.")
        return modified_html_path

    except Exception as e:
        print(f"Error in add_labels_in_image_via_html: {e}")
        raise

def html_to_image(html_path: Path, output_image_path: Path, size=(512, 512)):
    """
    Converts the labeled HTML to an image using Html2Image.

    :param html_path: Path to the labeled HTML file.
    :param output_image_path: Path where the output image will be saved.
    :param size: Tuple specifying the size (width, height) of the output image.
    """
    try:
        # Ensure the HTML file exists
        if not html_path.is_file():
            raise FileNotFoundError(f"The labeled HTML file '{html_path}' does not exist.")

        # Initialize Html2Image
        hti = Html2Image()

        # Set output directory to the same as the HTML file
        hti.output_path = html_path.parent

        # Convert HTML to image
        hti.screenshot(
            html_file=str(html_path.resolve()),
            save_as=output_image_path.name,
            size=size
        )

        print(f"Labeled image has been saved to '{output_image_path}'.")
    
    except Exception as e:
        print(f"Error in html_to_image: {e}")
        raise

def generate_image(parameters, img_src: str, template_html: str = 'template.html'):
    """
    Orchestrates the labeling of the image by updating HTML and converting it to an image.

    :param parameters: List of tuples containing (left, top, width, height, label_number).
    :param img_src: Path to the input image.
    :param template_html: Path to the HTML template.
    """
    try:
        img_src_path = Path(img_src).resolve()
        template_html_path = Path(template_html).resolve()

        # Validate the input image file
        if not img_src_path.is_file():
            raise FileNotFoundError(f"The image file '{img_src_path}' does not exist.")

        # Generate the labeled HTML
        labeled_html_path = add_labels_in_image_via_html(parameters, img_src_path, template_html_path)

        # Define the output image path
        output_image_path = img_src_path.parent / f"{img_src_path.stem}_labeled.jpg"

        # Convert the labeled HTML to an image
        html_to_image(labeled_html_path, output_image_path)

    except Exception as e:
        print(f"Error in generate_image: {e}")

# Function to encode the image
def encode_image(image_path):
    with open(image_path, "rb") as image_file:
        return base64.b64encode(image_file.read()).decode("utf-8")
    
def GenerateImages(labels, images_src, template_html = 'template.html'):
    labels = json.loads(labels)
    parameters = []
    for label in labels['Labels']:
        parameters.append((label['left'], label['top'], label['width'], label['height'], label['DefectNumbering']))
    for img_src in images_src:
        generate_image(parameters, img_src, template_html)
