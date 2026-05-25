#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TensorFlow Lite 模型生成脚本
从 TensorFlow Hub / Keras 下载预训练模型并转换为 TFLite 格式

功能：
1. 下载 MobileNetV2 图像分类模型
2. 下载 EfficientDet-Lite 目标检测模型
3. 转换为 TensorFlow Lite 格式
4. 生成标签文件

使用方法：
    python download_tflite_models.py              # 下载所有模型
    python download_tflite_models.py --model mobilenet   # 只下载图像分类模型
    python download_tflite_models.py --model efficientdet  # 只下载目标检测模型

依赖：
    pip install tensorflow numpy
"""

import os
import sys
import json
import argparse
from pathlib import Path

try:
    import tensorflow as tf
    from tensorflow.keras.applications import MobileNetV2
    HAS_TF = True
except ImportError:
    HAS_TF = False
    print("⚠️ TensorFlow 未安装。请运行: pip install tensorflow")

try:
    import tensorflow_hub as hub
    HAS_TF_HUB = True
except ImportError:
    HAS_TF_HUB = False
    print("⚠️ TensorFlow Hub 未安装。将跳过某些模型")

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False
    print("⚠️ NumPy 未安装。请运行: pip install numpy")

BASE_DIR = Path(__file__).parent
MODELS_DIR = BASE_DIR / "models"

IMAGE_NET_LABELS = [
    "tench", "goldfish", "great white shark", "tiger shark", "hammerhead",
    "electric ray", "stingray", "cock", "hen", "ostrich", "brambling",
    "goldfinch", "house finch", "junco", "indigo bunting", "robin",
    "bulbul", "jay", "magpie", "chickadee", "water ouzel", "kite",
    "bald eagle", "vulture", "great grey owl", "European fire salamander",
    "common newt", "eft", "spotted salamander", "axolotl", "bullfrog",
    "tree frog", "tailed frog", "loggerhead", "leatherback turtle",
    "mud turtle", "terrapin", "box turtle", "banded gecko", "common iguana",
    "American chameleon", "whiptail", "agama", "frilled lizard", "alligator lizard",
    "Gila monster", "green lizard", "African chameleon", "Komodo dragon",
    "African crocodile", "American alligator", "triceratops", "thunder snake",
    "ringneck snake", "hognose snake", "green snake", "king snake",
    "garter snake", "water snake", "vine snake", "night snake",
    "boa constrictor", "rock python", "Indian cobra", "green mamba",
    "sea snake", "horned viper", "diamondback", "sidewinder", "trilobite",
    "harvestman", "scorpion", "black and gold garden spider", "barn spider",
    "garden spider", "black widow", "tarantula", "wolf spider", "tick",
    "centipede", "black grouse", "ptarmigan", "ruffed grouse", "prairie chicken",
    "peacock", "quail", "partridge", "African grey", "macaw", "sulphur-crested cockatoo",
    "lorikeet", "coucal", "bee eater", "hornbill", "hummingbird", "jacamar",
    "toucan", "drake", "red-breasted merganser", "goose", "black swan",
    "tusker", "echidna", "platypus", "wallaby", "koala", "wombat",
    "jellyfish", "sea anemone", "brain coral", "flatworm", "nematode",
    "conch", "snail", "slug", "sea slug", "chiton", "chambered nautilus",
    "Dungeness crab", "rock crab", "fiddler crab", "king crab",
    "American lobster", "spiny lobster", "crayfish", "hermit crab",
    "isopod", "white stork", "black stork", "spoonbill", "flamingo",
    "little blue heron", "American egret", "bittern", "crane", "limpkin",
    "European gallinule", "American coot", "bustard", "ruddy turnstone",
    "red-backed sandpiper", "redshank", "dowitcher", "oystercatcher",
    "pelican", "king penguin", "albatross", "grey whale", "killer whale",
    "dugong", "sea lion", "Chihuahua", "Japanese spaniel", "Maltese dog",
    "Pekinese", "Shih-Tzu", "Blenheim spaniel", "papillon", "toy terrier",
    "Rhodesian ridgeback", "Afghan hound", "basset", "beagle",
    "bloodhound", "bluetick", "black-and-tan coonhound", "Walker hound",
    "English foxhound", "redbone", "borzoi", "Irish wolfhound",
    "Italian greyhound", "whippet", "Ibizan hound", "Norwegian elkhound",
    "otterhound", "Saluki", "Scottish deerhound", "Weimaraner",
    "Staffordshire bullterrier", "American Staffordshire terrier",
    "Bedlington terrier", "Border terrier", "Kerry blue terrier",
    "Irish terrier", "Norfolk terrier", "Norwich terrier", "Yorkshire terrier",
    "wire-haired fox terrier", "Lakeland terrier", "Sealyham terrier",
    "Airedale", "cairn", "Australian terrier", "Dandie Dinmont",
    "Boston bull", "miniature schnauzer", "giant schnauzer",
    "standard schnauzer", "Scotch terrier", "Tibetan terrier",
    "silky terrier", "soft-coated wheaten terrier", "West Highland white terrier",
    "Lhasa", "flat-coated retriever", "curly-coated retriever",
    "golden retriever", "Labrador retriever", "Chesapeake Bay retriever",
    "German short-haired pointer", "vizsla", "English setter",
    "Irish setter", "Gordon setter", "Brittany spaniel",
    "clumber", "English springer", "Welsh springer spaniel",
    "cocker spaniel", "Sussex spaniel", "Irish water spaniel",
    "kuvasz", "schipperke", "groenendael", "malinois", "briard",
    "kelpie", "komondor", "Old English sheepdog", "Shetland sheepdog",
    "collie", "Border collie", "Bouvier des Flandres", "Rottweiler",
    "German shepherd", "Doberman", "miniature pinscher",
    "Greater Swiss Mountain dog", "Bernese mountain dog", "Appenzeller",
    "EntleBucher", "boxer", "bull mastiff", "Tibetan mastiff",
    "French bulldog", "Great Dane", "Saint Bernard", "Eskimo dog",
    "malamute", "Siberian husky", "dalmatian", "affenpinscher",
    "basenji", "pug", "Leonberg", "Newfoundland", "Great Pyrenees",
    "Samoyed", "Pomeranian", "chow", "keeshond", "Brabancon griffon",
    "Pembroke", "Cardigan", "toy poodle", "miniature poodle",
    "standard poodle", "Mexican hairless", "timber wolf",
    "white wolf", "red wolf", "coyote", "dingo", "dhole",
    "African hunting dog", "hyena", "red fox", "kit fox",
    "Arctic fox", "grey fox", "tabby", "tiger cat", "Persian cat",
    "Siamese cat", "Egyptian cat", "cougar", "lynx", "leopard",
    "snow leopard", "jaguar", "lion", "tiger", "cheetah",
    "brown bear", "American black bear", "ice bear", "sloth bear",
    "mongoose", "meerkat", "tiger beetle", "ladybug", "ground beetle",
    "long-horned beetle", "leaf beetle", "dung beetle", "rhinoceros beetle",
    "weevil", "fly", "bee", "ant", "grasshopper", "cricket",
    "stick insect", "cockroach", "mantis", "cicada", "leafhopper",
    "lacewing", "dragonfly", "damselfly", "admiral", "ringlet",
    "monarch", "cabbage butterfly", "sulphur butterfly",
    "lycaenid", "starfish", "sea urchin", "sea cucumber", "wood rabbit",
    "hare", "Angora", "hamster", "porcupine", "fox squirrel",
    "marmot", "beaver", "guinea pig", "sorrel", "zebra",
    "hog", "wild boar", "warthog", "hippopotamus", "ox",
    "water buffalo", "bison", "ram", "bighorn", "ibex", "hartebeest",
    "impala", "gazelle", "Arabian camel", "llama", "weasel",
    "mink", "polecat", "black-footed ferret", "otter", "skunk",
    "badger", "armadillo", "three-toed sloth", "orangutan", "gorilla",
    "chimpanzee", "gibbon", "siamang", "guenon", "patas", "baboon",
    "macaque", "langur", "colobus", "proboscis monkey", "marmoset",
    "capuchin", "howler monkey", "titi", "spider monkey", "squirrel monkey",
    "Madagascar cat", "indri", "Indian elephant", "African elephant",
    "lesser panda", "giant panda", "barracouta", "eel",
    "coho", "rock beauty", "anemone fish", "sturgeon", "gar",
    "lionfish", "pufferfish", "abacus", "abaya", "academic gown",
    "accordion", "acoustic guitar", "aircraft carrier", "airliner",
    "airship", "altar", "ambulance", "amphibian", "analog clock",
    "apiary", "apron", "ashcan", "assault rifle", "backpack",
    "bakery", "balance beam", "balloon", "ballpoint", "Band Aid",
    "banjo", "bannister", "barbell", "barber chair", "barbershop",
    "barn", "barometer", "barrel", "barrow", "baseball", "basketball",
    "bassinet", "bassoon", "bathing cap", "bath towel", "bathtub",
    "beach wagon", "beacon", "beaker", "bearskin", "beer bottle",
    "beer glass", "bell cote", "bib", "bicycle-built-for-two", "bikini",
    "binder", "binoculars", "birdhouse", "boathouse", "bobsled",
    "bolo tie", "bonnet", "bookcase", "bookshop", "bottlecap",
    "bow", "bow tie", "brass", "brassiere", "breakwater",
    "breastplate", "broom", "bucket", "buckle", "bulletproof vest",
    "bullet train", "butcher shop", "cab", "caldron", "candle",
    "cannon", "canoe", "can opener", "cardigan", "car mirror",
    "carousel", "carpenter's kit", "carton", "car wheel", "cash machine",
    "cassette", "cassette player", "castle", "catamaran", "CD player",
    "cello", "cellular telephone", "chain", "chainlink fence",
    "chain mail", "chain saw", "chest", "chiffonier", "chime",
    "china cabinet", "Christmas stocking", "church", "cinema", "cleaver",
    "cliff dwelling", "cloak", "clog", "cocktail shaker",
    "coffee mug", "coffeepot", "coil", "combination lock",
    "computer keyboard", "confectionery", "container ship", "convertible",
    "corkscrew", "cornet", "cowboy boot", "cowboy hat", "cradle",
    "crane", "crash helmet", "crate", "crib", "Crock Pot",
    "croquet ball", "crutch", "cuirass", "dam", "desk", "desktop computer",
    "dial telephone", "diaper", "digital clock", "digital watch",
    "dining table", "dishrag", "dishwasher", "disk brake",
    "dock", "dogsled", "dome", "doormat", "drilling rig", "drum",
    "drumstick", "dumbbell", "Dutch oven", "electric fan",
    "electric guitar", "electric locomotive", "entertainment center",
    "envelope", "espresso maker", "face powder", "feather boa",
    "file", "fireboat", "fire engine", "fire screen", "flagpole",
    "flute", "folding chair", "football helmet", "forklift",
    "fountain", "fountain pen", "four-poster", "freight car",
    "French horn", "frying pan", "fur coat", "garbage truck",
    "gas pump", "goblet", "go-kart", "golf ball", "golfcart",
    "gondola", "gong", "gown", "grand piano", "greenhouse",
    "grille", "grocery store", "guillotine", "hair slide", "hair spray",
    "half track", "hammer", "hamper", "hand blower", "hand-held computer",
    "handkerchief", "hard disc", "harmonica", "harp", "harvester",
    "hatchet", "holster", "home theater", "honeycomb", "hook",
    "hoopskirt", "horizontal bar", "horse cart", "hourglass", "iPod",
    "iron", "jack-o'-lantern", "jean", "jeep", "jersey",
    "jigsaw puzzle", "jinrikisha", "joystick", "kimono", "knee pad",
    "knot", "lab coat", "ladle", "lampshade", "laptop",
    "lawn mower", "lens cap", "letter opener", "library", "lifeboat",
    "lighter", "limousine", "liner", "lipstick", "Loafer",
    "loupe", "lumbermill", "magnetic compass", "mailbag",
    "mailbox", "maillot", "manhole cover", "maraca", "marimba",
    "mask", "matchstick", "maypole", "maze", "measuring cup",
    "medicine chest", "megalith", "microphone", "microwave", "military uniform",
    "milk can", "minibus", "miniskirt", "minivan", "missile",
    "mitten", "mixing bowl", "mobile home", "Model T", "modem",
    "monastery", "monitor", "moped", "mortar", "mortarboard",
    "mosque", "mosquito net", "motor scooter", "mountain bike",
    "mountain tent", "mouse", "mousetrap", "moving van",
    "muzzle", "nail", "neck brace", "necklace", "nipple",
    "notebook", "obelisk", "oboe", "ocarina", "odometer",
    "oil filter", "organ", "oscilloscope", "overskirt", "oxcart",
    "oxygen mask", "packet", "paddle", "paddlewheel", "padlock",
    "paintbrush", "pajama", "palace", "panpipe", "paper towel",
    "parachute", "parallel bars", "park bench", "parking meter",
    "passenger car", "patio", "pay-phone", "pedestal", "pencil box",
    "pencil sharpener", "perfume", "Petri dish", "photocopier",
    "pick", "pickelhaube", "picket fence", "pickup", "pier",
    "piggy bank", "pill bottle", "pillow", "ping-pong ball",
    "pinwheel", "pirate", "pitcher", "plane", "planetarium",
    "plastic bag", "plate rack", "plow", "plunger", "Polaroid camera",
    "pole", "police van", "poncho", "pool table", "pop bottle",
    "pot", "potter's wheel", "power drill", "prayer rug",
    "printer", "prison", "projectile", "projector", "puck",
    "punching bag", "purse", "quill", "quilt", "racer", "racket",
    "radiator", "radio", "radio telescope", "rain barrel",
    "recreational vehicle", "reel", "reflex camera", "refrigerator",
    "remote control", "restaurant", "revolver", "rifle", "rocking chair",
    "rotisserie", "rubber eraser", "rugby ball", "rule",
    "running shoe", "safe", "safety pin", "saltshaker", "sandal",
    "sarong", "sax", "scabbard", "scale", "school bus", "schooner",
    "scoreboard", "screen", "screw", "screwdriver", "seat belt",
    "sewing machine", "shield", "shoe shop", "shoji", "shopping basket",
    "shopping cart", "shovel", "shower cap", "shower curtain",
    "ski", "ski mask", "sleeping bag", "slide rule", "sliding door",
    "slot", "snorkel", "snowmobile", "snowplow", "soap dispenser",
    "soccer ball", "sock", "solar dish", "sombrero", "soup bowl",
    "space bar", "space heater", "space shuttle", "spatula",
    "speedboat", "spider web", "spindle", "sports car",
    "spotlight", "stage", "steam locomotive", "steel arch bridge",
    "steel drum", "stethoscope", "stole", "stone wall",
    "stopwatch", "stove", "strainer", "streetcar", "stretcher",
    "studio couch", "stupa", "submarine", "suit", "sundial",
    "sunglass", "sunglasses", "sunscreen", "suspension bridge",
    "swab", "sweatshirt", "swimming trunks", "swing", "switch",
    "syringe", "table lamp", "tank", "tape player", "teapot",
    "teddy", "television", "tennis ball", "thatch", "theater curtain",
    "thimble", "thresher", "threshold", "toaster", "tobacco shop",
    "toilet seat", "torch", "totem pole", "tow truck", "toyshop",
    "tractor", "trailer truck", "tray", "trench coat",
    "tricycle", "trimaran", "tripod", "triumphal arch", "trolleybus",
    "trombone", "tub", "turnstile", "typewriter keyboard",
    "umbrella", "unicycle", "upright", "vacuum", "vase",
    "vault", "velvet", "vending machine", "vestment", "viaduct",
    "violin", "volleyball", "waffle iron", "wall clock",
    "wallet", "wardrobe", "warplane", "washbasin", "washer",
    "water bottle", "water jug", "water tower", "whiskey jug", "whistle",
    "wig", "window screen", "window shade", "Windsor tie",
    "wine bottle", "wing", "wok", "wooden spoon", "wool", "worm fence",
    "wreck", "yawl", "yurt", "web site", "comic book",
    "crossword puzzle", "street sign", "traffic light", "book jacket",
    "menu", "plate", "guacamole", "consomme", "hot pot", "trifle",
    "ice cream", "ice lolly", "French loaf", "bagel", "pretzel",
    "cheeseburger", "hotdog", "mashed potato", "head cabbage",
    "broccoli", "cauliflower", "zucchini", "spaghetti squash",
    "acorn squash", "butternut squash", "cucumber", "artichoke",
    "bell pepper", "cardoon", "mushroom", "Granny Smith", "strawberry",
    "orange", "lemon", "fig", "pineapple", "banana", "jackfruit",
    "custard apple", "pomegranate", "hay", "carbonara", "chocolate sauce",
    "dough", "meat loaf", "pizza", "potpie", "burrito", "red wine",
    "espresso", "cup", "eggnog", "alp", "bubble", "cliff",
    "coral reef", "geyser", "lakeside", "promontory", "sandbar",
    "seashore", "valley", "volcano", "ballplayer", "groom",
    "scuba diver", "rapeseed", "daisy", "yellow lady's slipper",
    "corn", "acorn", "hip", "buckeye", "coral fungus",
    "agaric", "gyromitra", "stinkhorn", "earthstar",
    "hen-of-the-woods", "bolete", "ear", "toilet tissue"
]

COCO_LABELS = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus",
    "train", "truck", "boat", "traffic light", "fire hydrant",
    "street sign", "stop sign", "parking meter", "bench", "bird",
    "cat", "dog", "horse", "sheep", "cow", "elephant", "bear",
    "zebra", "giraffe", "hat", "backpack", "umbrella", "shoe",
    "eye glasses", "handbag", "tie", "suitcase", "frisbee", "skis",
    "snowboard", "sports ball", "kite", "baseball bat",
    "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "plate", "wine glass", "cup", "fork", "knife",
    "spoon", "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
    "chair", "couch", "potted plant", "bed", "mirror",
    "dining table", "window", "desk", "toilet", "door", "tv",
    "laptop", "mouse", "remote", "keyboard", "cell phone",
    "microwave", "oven", "toaster", "sink", "refrigerator",
    "blender", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush", "hair brush"
]


def generate_labels():
    """生成标签文件"""
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    
    print("\n📝 生成 ImageNet 标签文件...")
    imagenet_path = MODELS_DIR / "imagenet_labels.txt"
    with open(imagenet_path, "w", encoding="utf-8") as f:
        for label in IMAGE_NET_LABELS:
            f.write(label + "\n")
    print(f"   ✅ 已生成 {len(IMAGE_NET_LABELS)} 个标签: {imagenet_path}")
    
    print("\n📝 生成 COCO 标签文件...")
    coco_path = MODELS_DIR / "coco_labels.txt"
    with open(coco_path, "w", encoding="utf-8") as f:
        for label in COCO_LABELS:
            f.write(label + "\n")
    print(f"   ✅ 已生成 {len(COCO_LABELS)} 个标签: {coco_path}")
    
    return True


def download_mobilenetv2():
    """下载并转换 MobileNetV2 模型"""
    if not HAS_TF:
        print("❌ 需要 TensorFlow 才能下载和转换模型")
        print("   请运行: pip install tensorflow")
        return False
    
    tflite_path = MODELS_DIR / "mobilenet_v2_1.0_224.tflite"
    if tflite_path.exists():
        size_mb = tflite_path.stat().st_size / (1024 * 1024)
        print(f"⏭️ MobileNetV2 模型已存在，跳过")
        print(f"   文件路径: {tflite_path}")
        print(f"   文件大小: {size_mb:.2f} MB")
        return True
    
    print("\n📦 准备下载 MobileNetV2 (ImageNet 预训练)...")
    print("   这将从 TensorFlow Hub 下载约 14MB 的模型权重...")
    print()
    
    try:
        model = MobileNetV2(
            weights='imagenet',
            input_shape=(224, 224, 3),
            include_top=True
        )
        
        print("✅ 模型加载完成")
        print(f"   输入形状: {model.input_shape}")
        print(f"   输出类别: {model.output_shape[-1]}")
        print()
        
        print("🔄 转换为 TensorFlow Lite 格式...")
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_model = converter.convert()
        
        with open(tflite_path, "wb") as f:
            f.write(tflite_model)
        
        size_mb = tflite_path.stat().st_size / (1024 * 1024)
        print(f"✅ 转换完成！")
        print(f"   文件路径: {tflite_path}")
        print(f"   文件大小: {size_mb:.2f} MB")
        
        return True
        
    except Exception as e:
        print(f"❌ 下载/转换失败: {str(e)}")
        print()
        print("💡 可能的原因:")
        print("   1. 网络连接问题（无法访问 TensorFlow Hub）")
        print("   2. TensorFlow 版本问题")
        print()
        print("💡 替代方案:")
        print("   1. 使用 VPN/代理后重试")
        print("   2. 手动从以下地址下载预转换的 TFLite 模型:")
        print("      - https://www.kaggle.com/models/tensorflow/mobilenet-v2")
        print("      - https://tfhub.dev/s?module-type=image-classification&tf-version=tf2")
        return False


def download_efficientdet_lite():
    """下载并转换 EfficientDet-Lite 目标检测模型"""
    if not HAS_TF:
        print("❌ 需要 TensorFlow 才能下载和转换模型")
        print("   请运行: pip install tensorflow")
        return False
    
    tflite_path = MODELS_DIR / "efficientdet_lite0.tflite"
    if tflite_path.exists():
        size_mb = tflite_path.stat().st_size / (1024 * 1024)
        print(f"⏭️ EfficientDet-Lite 模型已存在，跳过")
        print(f"   文件路径: {tflite_path}")
        print(f"   文件大小: {size_mb:.2f} MB")
        return True
    
    print("\n📦 准备下载 EfficientDet-Lite 0 (COCO 预训练)...")
    print("   这是一个轻量级目标检测模型，可检测 90 个常见物体...")
    print()
    
    model_urls = [
        "https://tfhub.dev/tensorflow/efficientdet/lite0/detection/1",
        "https://tfhub.dev/tensorflow/efficientdet/lite2/detection/1",
    ]
    
    for url in model_urls:
        try:
            print(f"🔄 尝试从 TensorFlow Hub 加载模型:")
            print(f"   {url}")
            print()
            
            detector = hub.load(url)
            
            print("✅ 模型加载完成")
            print()
            
            print("🔄 转换为 TensorFlow Lite 格式...")
            
            class DetectorModel(tf.Module):
                def __init__(self, detector):
                    self.detector = detector
                
                @tf.function(input_signature=[tf.TensorSpec(shape=[1, 320, 320, 3], dtype=tf.uint8)])
                def __call__(self, images):
                    return self.detector(images)
            
            model = DetectorModel(detector)
            
            converter = tf.lite.TFLiteConverter.from_concrete_functions(
                [model.__call__.get_concrete_function()]
            )
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            tflite_model = converter.convert()
            
            with open(tflite_path, "wb") as f:
                f.write(tflite_model)
            
            size_mb = tflite_path.stat().st_size / (1024 * 1024)
            print(f"✅ 转换完成！")
            print(f"   文件路径: {tflite_path}")
            print(f"   文件大小: {size_mb:.2f} MB")
            
            return True
            
        except Exception as e:
            print(f"❌ 下载/转换失败: {str(e)}")
            print(f"   尝试下一个模型...")
            print()
    
    print("\n⚠️ 所有模型都下载失败")
    print()
    print("💡 替代方案（推荐）:")
    print("   1. 手动下载预转换的 EfficientDet-Lite TFLite 模型:")
    print("      - Kaggle: https://www.kaggle.com/models")
    print("      - 搜索 'efficientdet lite tflite'")
    print()
    print("   2. 或使用其他目标检测模型:")
    print("      - YOLOv8n (需要转换为 TFLite)")
    print("      - SSD MobileNet")
    print()
    print(f"   3. 将 .tflite 模型文件放置到: {MODELS_DIR}")
    print("      并重命名为: efficientdet_lite0.tflite")
    
    return False


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='TensorFlow Lite 模型生成工具')
    parser.add_argument('--model', type=str, 
                       choices=['all', 'mobilenet', 'efficientdet'],
                       default='all',
                       help='要下载的模型类型 (默认: all)')
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("🤖 TensorFlow Lite 模型生成工具")
    print("=" * 60)
    print()
    
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    
    success = True
    
    success = generate_labels() and success
    
    if not HAS_TF:
        print("\n⚠️ TensorFlow 未安装，跳过模型下载和转换")
        print("   请运行: pip install tensorflow")
        print()
        print("💡 或者，您可以手动下载预转换的 TFLite 模型:")
        print("   1. 访问 Kaggle: https://www.kaggle.com/models/tensorflow/mobilenet-v2")
        print("   2. 下载 .tflite 格式的模型")
        print(f"   3. 放置到: {MODELS_DIR}")
        success = False
    else:
        if args.model in ['all', 'mobilenet']:
            success = download_mobilenetv2() and success
        
        if args.model in ['all', 'efficientdet']:
            if HAS_TF_HUB:
                success = download_efficientdet_lite() and success
            else:
                print("\n⚠️ TensorFlow Hub 未安装，跳过 EfficientDet-Lite 下载")
                print("   请运行: pip install tensorflow-hub")
                print()
                print("💡 或者，您可以手动下载预转换的 TFLite 模型")
    
    print()
    print("=" * 60)
    if success:
        print("✅ 所有任务完成！")
        print()
        print("📁 生成的文件:")
        for f in MODELS_DIR.glob("*.txt"):
            print(f"   - {f.name}")
        for f in MODELS_DIR.glob("*.tflite"):
            size = f.stat().st_size / (1024 * 1024)
            print(f"   - {f.name} ({size:.2f} MB)")
    else:
        print("⚠️ 部分任务未完成")
        print()
        print("📁 当前文件:")
        for f in MODELS_DIR.glob("*.txt"):
            print(f"   - {f.name}")
        for f in MODELS_DIR.glob("*.tflite"):
            size = f.stat().st_size / (1024 * 1024)
            print(f"   - {f.name} ({size:.2f} MB)")
        print()
        print("💡 下一步:")
        print("   1. 检查网络连接后重试")
        print("   2. 或手动下载 TFLite 模型文件")
        print(f"   3. 放置到: {MODELS_DIR}")
    print("=" * 60)
    
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
