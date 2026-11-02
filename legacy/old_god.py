from tkinter import *
from tkinter import ttk
import copy


def save_state(action_type, country=None, city=None, ecology=None):
    state = {
        'type': action_type,
        'ecology': ecology,

        'country_name': country.name if country else None,
        'country_balance': country.balance if country else None,
        'tech_in_research': country.tech_in_research if country else None,
        'bombs': country.bombs if country else None,
        'bombs_in_production': country.bombs_in_production if country else None,

        'city_name': city.name if city else None,
        'city_level': city.level if city else None,
        'city_income': city.city_income if city else None,
        'city_isalive': city.isalive if city else None,
        'city_shield': city.shield if city else None
    }
    action_stack.append(state)

def ctrlz():
    global ecology
    if action_stack:
        last_action = action_stack.pop()
        country_name = last_action['country_name']
        city_name = last_action['city_name']

        if last_action['ecology'] is not None:
            ecology = last_action['ecology']

        if country_name:
            for country in countries:
                if country.name == country_name:
                    if last_action['country_balance'] is not None:
                        country.balance = last_action['country_balance']
                    break

        if city_name:
            for country in countries:
                city = country.find_city(city_name)
                if city:
                    if last_action['city_level'] is not None:
                        city.level = last_action['city_level']
                    if last_action['city_income'] is not None:
                        city.city_income = last_action['city_income']
                    if last_action['city_isalive'] is not None:
                        city.isalive = last_action['city_isalive']
                    if last_action['city_shield'] is not None:
                        city.shield = last_action['city_shield']
                    break

        print(f"Действие '{last_action['type']}' отменено.")
    else:
        print("Нет действий для отмены.")

def upgrade_ecology(country):
    global ecology
    save_state('upgrade_ecology', ecology=ecology)
    if country.balance >= 200:
        country.balance -= 200
        ecology += 20
        if ecology > 100:
            ecology = 100
    else:
        print("Нет денег")

def launch(country, city_name):
    global countries
    for c in countries:
        target = c.find_city(city_name)
        if target:
            save_state('launch', country=country, city=target)
            if country.bombs > 0:
                if target.shield:
                    target.shield = False
                    print(f'Город {city_name} спас щит')
                else:
                    target.isalive = False
                    target.city_income = 0
                    print(f'Город {city_name} превращен в радиоактивный пепел')
                country.bombs -= 1
            else:
                print('Нет бомб')
            break
    else:
        print('Цель не найдена')

def update():
    global countries, turn
    for c in countries:
        c.next_turn()
    turn += 1
    action_stack.clear()
    print(f'{turn} ход')
def info(country):
    global ecology, turn
    with open(f'{country.name}_info.txt', 'w', encoding='utf-8') as file:
        file.write(f'Ход № {turn}, экология {ecology}%')
        file.write("\n")
        for attr, value in vars(country).items():
            if attr != 'cities' and attr != 'bombs_in_production' and attr != 'tech_in_research':
                file.write(f"{attr}: {value}\n")
        file.write("\n")
        for city in country.cities:
            for attr, value in vars(city).items():
                file.write(f"{attr}: {value}\n")
            file.write("\n")
class Country:
    def __init__(self, name, cities):
        self.name = name
        self.cities = cities
        self.balance = 1000
        self.income = (sum(i.city_income for i in self.cities if i.isalive))
        self.tech = False
        self.tech_in_research = False
        self.bombs = 1
        self.bombs_in_production = 0

    def upgrade_city(self, city):
        save_state('upgrade_city', country=self, city=city)
        if self.balance >= 150:
            if city.isalive:
                city.level += 1
                city.city_income = levels[city.level]
                self.balance -= 150
                print(f'Город {city.name} улучшен')
            else:
                print('нечего улучшать')
        else:
            print('денег нет')

    def shield_city(self, city):
        save_state('shield_city', country=self, city=city)
        if self.balance >= 250:
            if city.isalive:
                if not city.shield:
                    city.shield = True
                    self.balance -= 150
                    print(f'На город {city.name} поставлен цит')
                else:
                    print('уже стоит')
            else:
                print('нечего защищать')
        else:
            print('нет денег')

    def researh(self):
        global ecology
        save_state('research', country=self, ecology=ecology)
        if not self.tech:
            if self.balance >= 500:
                self.balance -= 500
                self.tech_in_research = True
                ecology -= 5
                print(f'Начато изучении технологии в {self.name}')
            else:
                print('нет денег')
        else:
            print('уже изучена')

    def produce(self):
        global ecology
        save_state('produce', country=self, ecology=ecology)
        if self.tech:
            if self.balance >= 150:
                self.bombs_in_production += 1
                self.balance -= 150
                ecology -= 4
                print(f'Запущено производство бомбы в {self.name}')
            else:
                print('нет денег')
        else:
            print('нет технологии')

    def next_turn(self):
        self.income = (sum(i.city_income for i in self.cities if i.isalive))
        self.balance += self.income

        self.bombs += self.bombs_in_production
        self.bombs_in_production = 0

        if self.tech_in_research:
            self.tech = True
            self.tech_in_research = False

        info(self)

    def find_city(self, city_name):
        for city in self.cities:
            if city.name == city_name:
                return city
        return None
class City:
    def __init__(self, name):
        self.name = name
        self.level = 0
        self.city_income = levels[self.level]
        self.isalive = True
        self.shield = False

def add_buttons(country, n):
    name = ttk.Label(text=f'{country.name}', font=("Arial", 14))
    name.grid(row=n, rowspan=3)
    for i in range(4):
        city = country.cities[i]
        btn = ttk.Button(text=f"Улучшить {country.cities[i].name}", command=lambda x=city: country.upgrade_city(x))
        btn.grid(row=n, column=i + 1)
    for i in range(4):
        city = country.cities[i]
        btn = ttk.Button(text=f"Поставить щит на {country.cities[i].name}", command=lambda x=city: country.shield_city(x))
        btn.grid(row=n + 1, column=i + 1)

    tech = ttk.Button(text='Изучить технологию', command=lambda: country.researh())
    tech.grid(row=n + 2,column=1)

    bomb = ttk.Button(text='Произвести бомбу', command=lambda: country.produce())
    bomb.grid(row=n + 2, column=2)

    eco = ttk.Button(text='Улучшить экологию', command=lambda: upgrade_ecology(country))
    eco.grid(row=n + 2, column=3)

    drop = ttk.Button(text='Сбросить бомбу на', command=lambda: launch(country, target.get()))
    drop.grid(row=n + 2, column=4)

    target = ttk.Entry()
    target.grid(row=n+2,column=5)

    space = ttk.Label(text = '')
    space.grid(row=n+3, column=0, columnspan=5)

levels = (35, 70, 140, 210, 280, 350, 420, 490)
ecology = 100
turn = 1
action_stack = []

Moscow = City('Москва')
Vladik = City('Владивосток')
SPB = City('Санкт-Петербург')
NSK = City('Новосибирск')
RU_cities = [Moscow, Vladik, SPB, NSK]

LA = City('Лос-Анжелес')
NY = City('Нью-Йорк')
WA = City('Вашингтон')
Chicago = City('Чикаго')
US_cities = [LA, NY, WA, Chicago]

Beijing = City('Пекин')
Shanghai = City('Шанхай')
Wuhan = City('Ухань')
Guangzhou = City('Гуанчжоу')
CN_cities = [Beijing, Shanghai, Wuhan, Guangzhou]

Tel_Aviv = City('Тель-Авив')
Jerusalem = City('Иерусалим')
Haifa = City('Хайфа')
Bekzhan = City('Биробиджан')
JW_cities = [Tel_Aviv, Jerusalem, Haifa, Bekzhan]

Berlin = City('Берлин')
Munich = City('Мюнхен')
Hamburg = City('Гамбург')
Koln = City('Кельн')
DE_cities = [Berlin, Munich, Hamburg, Koln]

Russia = Country('Россия',RU_cities)
USA = Country('США', US_cities)
China = Country('Китай', CN_cities)
Israel = Country('Израиль', JW_cities)
Germany = Country('Германия', DE_cities)

countries = [Russia, USA, China, Israel, Germany]

root = Tk()
root.title("GoSpOdA")
root.geometry("1000x600")

add_buttons(Russia, 0)
add_buttons(USA, 4)
add_buttons(China, 8)
add_buttons(Israel, 12)
add_buttons(Germany, 16)

undo = ttk.Button(text='Отменить действие', command=lambda: ctrlz())
undo.grid(column=0, columnspan=6)

next = ttk.Button(text='Следующий ход', command=lambda: update())
next.grid(column=0, columnspan=6)

eco = ttk.Label(text=f'{ecology}')
eco.grid()

root.mainloop()


